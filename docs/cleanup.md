# Javers Snapshot Cleanup

→ [Deutsche Version](#deutsch)

---

## English

### Problem

Javers provides no built-in mechanism for cleaning up audit tables. The tables `jv_snapshot`, `jv_commit`, `jv_commit_property`, and `jv_global_id` grow without bound. In development and test environments — but also in high-write production systems — this leads to performance degradation and increasing storage consumption.

### Why a simple DELETE does not work

The naive approach — `DELETE FROM jv_snapshot WHERE commit_date < ?` — destroys the consistency of the audit history:

**Structure of Javers snapshots:**

| Type | `changed_properties` | `state` |
|---|---|---|
| `INITIAL` | **all** property names | complete object state |
| `UPDATE` | only the **changed** property names | complete object state |
| `TERMINAL` | empty | empty (entity deleted) |

The `state` column always holds the full object state — that is not a problem. The issue is `changed_properties`: an `UPDATE` snapshot only lists the fields that changed. When the original `INITIAL` snapshot is deleted, the oldest remaining `UPDATE` snapshot must be **promoted**:

1. `type` → `INITIAL`
2. `changed_properties` → all property names (from the `state` JSON)

Only then does Javers correctly interpret that snapshot as a complete initial creation of the object.

**Additionally:** Foreign key references from `jv_snapshot` to `jv_commit` and `jv_global_id` impose a specific deletion order.

### Solution: `JaversCleanupService`

```java
@Autowired
JaversCleanupService cleanupService;

// Keep only the last 30 snapshots per entity
CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(30));

// Delete everything older than 90 days, keep at least 1 snapshot per entity
CleanupResult result = cleanupService.cleanup(CleanupPolicy.olderThan(90, 1));
```

### Algorithm

The cleanup runs in three phases to handle both snapshot promotion and **cross-entity reference integrity** (see next section):

```
Phase 1 — Compute proposed deletions:
  For each GlobalId in jv_snapshot:
    Load all snapshots (ascending by version)
    Policy selects: which ones should be deleted?

Phase 2 — Reference scan (hybrid):
  Read the state JSON of all retained snapshots (entities AND Value Objects)
  Recursively extract {"entity": "...", "cdoId": ...} patterns
  → Map: globalIdPk → earliest commitDate of any reference to that entity

Phase 3 — Anchor rescue:
  For each referenced entity:
    Does the oldest retained snapshot predate the earliest reference date?
    No → rescue the most recent deletion candidate with commitDate ≤ reference date

Execution:
  For each GlobalId with a non-empty deletion set:
    Inspect the oldest remaining snapshot:
      If type = 'UPDATE' → promote to INITIAL
      (property names loaded via Javers CdoSnapshot API, not JSON parsing)
    DELETE jv_snapshot WHERE snapshot_pk IN (...)

After all passes:
  DELETE jv_commit_property for orphaned commits
  DELETE jv_commit for orphaned commits
  DELETE jv_global_id (children first, then parents)
```

### Reference integrity for entity associations

Javers stores entity references (e.g. `@ManyToOne`) not as foreign keys to snapshots, but as a JSON reference inside the `state` field:

```json
{ "customer": { "entity": "de.example.Customer", "cdoId": 5 } }
```

**The problem without protection:** If `Order v1` was created at time T when `Customer v1` was the current state, and the policy later deletes `Customer v1` (because newer snapshots exist), Javers returns the wrong or no customer state when queried historically at time T.

**The solution (hybrid approach):** Phase 2 scans the `state` JSON of **all** retained snapshots — both entities and Value Objects (e.g. `@Embeddable` types with `@ManyToOne` fields) — and determines, for each referenced entity, the earliest date at which a reference to it exists. Phase 3 ensures that every referenced entity retains at least one snapshot with `commitDate ≤ that date` — rescuing an otherwise-deleted snapshot if necessary.

**Known limitation:** Only direct references are protected. Cascades (A → B → C) are not automatically resolved.

### API

```java
// count-based: keep exactly N most-recent snapshots per entity
CleanupPolicy.keepLatest(int count)

// time-based: delete snapshots older than N days, keep at least minKeep
CleanupPolicy.olderThan(int days, int minKeep)

// result
record CleanupResult(
    int promotedSnapshots,   // UPDATE → INITIAL promotions
    int deletedSnapshots,
    int deletedCommits,
    int rescuedSnapshots     // anchor rescues for reference integrity
)
```

**`olderThan` — interaction between `days` and `minKeep`:**  
`minKeep` acts as an absolute floor regardless of age. Even if all 10 snapshots of an entity are older than `days` days, the `minKeep` most recent are always retained. Example: `olderThan(90, 5)` deletes at most 5 out of 10 snapshots — even if all 10 exceed the age limit.

**Which policy to choose?**

| Scenario | Recommendation |
|---|---|
| Development / testing: keep a small recent window | `keepLatest(5)` |
| Production: rolling time window with a safety floor | `olderThan(180, 1)` |
| Regulatory: exact expiry date with a retention buffer | `olderThan(365, 3)` |

### `findChanges()` after a cleanup

After a promotion (UPDATE → INITIAL), `javers.findChanges()` returns **three change types** for the cleaned-up entity:

| Type | Meaning |
|---|---|
| `NewObject` | Entity "created" at the promoted INITIAL snapshot |
| `InitialValueChange` | Property values at the time of the promoted INITIAL |
| `ValueChange` | Actual diffs between consecutive snapshots |

To filter for real update diffs in application code: `ch.getClass() == ValueChange.class` excludes `InitialValueChange` (a subtype) and returns only true update changes.

**Limitation — gaps in the snapshot chain:**  
When an intermediate snapshot is deleted without becoming the anchor (e.g. v1, v3, v4 remain after deleting v2), `javers.findChanges()` throws `IllegalArgumentException` inside `SnapshotDiffer` because Javers cannot reconstruct a diff across the gap. This situation arises when the rescue mechanism saves a different snapshot than the one at the gap position (it saves the earliest possible anchor, not the next-newest).

In this case, use `javers.findSnapshots()` and compare states directly:

```java
List<CdoSnapshot> chain = javers.findSnapshots(QueryBuilder.byInstance(entity).build())
        .stream()
        .sorted(Comparator.comparingLong(CdoSnapshot::getVersion))
        .toList();

// States are directly readable, gap is visible in version numbers:
chain.get(0).getState().getPropertyValue("city"); // Berlin  (v1 — anchor)
chain.get(1).getState().getPropertyValue("city"); // Hamburg (v3 — v2/Munich deleted)
chain.get(2).getState().getPropertyValue("city"); // Frankfurt (v4)
```

Once subsequent cleanups promote the anchor to INITIAL (e.g. v4 becomes INITIAL and older snapshots are removed), the chain becomes gap-free and `findChanges()` works correctly again.

### Setting up a scheduled cleanup

```java
@Component
public class JaversCleanupJob {

    private final JaversCleanupService cleanupService;

    @Scheduled(cron = "0 0 2 * * SUN")  // every Sunday at 02:00
    public void run() {
        CleanupResult result = cleanupService.cleanup(
            CleanupPolicy.olderThan(180, 1)
        );
        log.info("Javers cleanup: {}", result);
    }
}
```

### Important notes

- The service runs in a single transaction per `cleanup()` call. For very large tables (millions of snapshots), the policy should be restricted accordingly and the job scheduled frequently to avoid oversized transactions.
- After a cleanup, restart the Javers application if an internal snapshot cache is configured (`CachingJaversRepository`).
- `TERMINAL` snapshots (deleted entities) are never promoted to INITIAL.
- The `jv_global_id` cleanup at the end of each run removes **all** orphaned entries — not only those created by this cleanup, but also any left behind by external data manipulation or partial migrations. This is a useful side effect that keeps the database consistent.

### Javers 7.11.x Schema

Column names changed compared to older versions:

| Table | PK column |
|---|---|
| `jv_snapshot` | `snapshot_pk` (previously: `id`) |
| `jv_commit` | `commit_pk` (previously: `id`) |
| `jv_global_id` | `global_id_pk` (previously: `id`) |

---

## Deutsch

### Problem

Javers bietet von sich aus keine Möglichkeit, die Audit-Tabellen zu bereinigen. Die Tabellen `jv_snapshot`, `jv_commit`, `jv_commit_property` und `jv_global_id` wachsen unbegrenzt. In Entwicklungs- und Testumgebungen, aber auch in Produktionssystemen mit hohem Schreibaufkommen, führt das zu Performanceproblemen und steigendem Speicherbedarf.

### Warum einfaches DELETE nicht funktioniert

Der naive Ansatz — `DELETE FROM jv_snapshot WHERE commit_date < ?` — zerstört die Konsistenz der Audit-Historie:

**Struktur der Javers-Snapshots:**

| Typ | `changed_properties` | `state` |
|---|---|---|
| `INITIAL` | **alle** Property-Namen | vollständiger Objektzustand |
| `UPDATE` | nur die **geänderten** Property-Namen | vollständiger Objektzustand |
| `TERMINAL` | leer | leer (Entity gelöscht) |

Der `state`-Spalte enthält immer den vollständigen Zustand — das ist kein Problem. Das Problem ist `changed_properties`: Ein `UPDATE`-Snapshot enthält dort nur die geänderten Felder. Wenn der ursprüngliche `INITIAL`-Snapshot gelöscht wird, muss der älteste verbleibende `UPDATE`-Snapshot **befördert** werden:

1. `type` → `INITIAL`
2. `changed_properties` → alle Property-Namen (aus dem `state`-JSON)

Nur so interpretiert Javers diesen Snapshot korrekt als vollständige Erstanlage des Objekts.

**Außerdem:** Referenzen aus `jv_snapshot` auf `jv_commit` und `jv_global_id` (Foreign Keys) erzwingen eine bestimmte Löschreihenfolge.

### Lösung: `JaversCleanupService`

```java
@Autowired
JaversCleanupService cleanupService;

// Nur die letzten 30 Snapshots pro Entity behalten
CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(30));

// Alles älter als 90 Tage löschen, mindestens 1 Snapshot pro Entity behalten
CleanupResult result = cleanupService.cleanup(CleanupPolicy.olderThan(90, 1));
```

### Algorithmus

Der Cleanup läuft in drei Phasen, um neben Beförderungen auch **entity-übergreifende Referenzintegrität** zu wahren (siehe nächster Abschnitt):

```
Phase 1 — Proposed Deletions berechnen:
  Für jede GlobalId in jv_snapshot:
    Alle Snapshots laden (aufsteigend nach Version)
    Policy bestimmt: welche sollen gelöscht werden?

Phase 2 — Referenz-Scan (Hybrid):
  state-JSON aller verbleibenden Snapshots einlesen (Entities UND Value Objects)
  {"entity": "...", "cdoId": ...}-Muster rekursiv extrahieren
  → Map: globalIdPk → frühestes commitDate einer Referenz auf diese Entity

Phase 3 — Anker-Rettung:
  Für jede referenzierte Entity:
    Liegt der älteste verbleibende Snapshot VOR dem frühesten Referenz-Datum?
    Nein → neuesten Löschkandidaten mit commitDate ≤ Referenz-Datum retten

Ausführung:
  Für jede GlobalId mit nicht-leerem Deletion-Set:
    Ältesten verbleibenden Snapshot prüfen:
      Falls type = 'UPDATE' → zu INITIAL befördern
      (Property-Namen via Javers CdoSnapshot API, nicht JSON-Parsing)
    DELETE jv_snapshot WHERE snapshot_pk IN (...)

Nach allen Durchläufen:
  DELETE jv_commit_property für verwaiste Commits
  DELETE jv_commit für verwaiste Commits
  DELETE jv_global_id (Kinder zuerst, dann Eltern)
```

### Referenzintegrität bei Entity-Verweisen

Javers speichert Entity-Referenzen (z.B. `@ManyToOne`) nicht als Fremdschlüssel auf Snapshots, sondern als JSON-Referenz im `state`-Feld:

```json
{ "customer": { "entity": "de.example.Customer", "cdoId": 5 } }
```

**Das Problem ohne Schutz:** Wenn `Order v1` zum Zeitpunkt T erstellt wurde, als `Customer v1` der aktuelle Zustand war, und die Policy danach `Customer v1` löscht (weil neuere Snapshots existieren), liefert Javers bei einer historischen Abfrage zu Zeitpunkt T den falschen oder gar keinen Customer-Zustand.

**Die Lösung (Hybrid-Ansatz):** Phase 2 scannt die `state`-JSON **aller** behaltenen Snapshots — sowohl von Entities als auch von Value Objects (z.B. `@Embeddable`-Typen mit `@ManyToOne`-Feldern) — und ermittelt pro referenzierter Entity das früheste Datum, an dem eine Referenz auf sie besteht. Phase 3 stellt sicher, dass jede referenzierte Entity mindestens einen Snapshot mit `commitDate ≤ diesem Datum` behält — nötigenfalls durch Rettung eines sonst gelöschten Snapshots.

**Bekannte Einschränkung:** Es werden nur direkte Referenzen geschützt. Kaskaden (A → B → C) werden nicht automatisch aufgelöst.

### API

```java
// count-basiert: genau N neueste Snapshots pro Entity behalten
CleanupPolicy.keepLatest(int count)

// zeitbasiert: Snapshots älter als N Tage löschen, mind. minKeep behalten
CleanupPolicy.olderThan(int days, int minKeep)

// Ergebnis
record CleanupResult(
    int promotedSnapshots,   // UPDATE → INITIAL Beförderungen
    int deletedSnapshots,
    int deletedCommits,
    int rescuedSnapshots     // Anker-Rettungen für Referenzintegrität
)
```

**`olderThan` — Zusammenspiel von `days` und `minKeep`:**  
`minKeep` wirkt als absolute Untergrenze, unabhängig vom Alter. Selbst wenn alle 10 Snapshots einer Entity älter als `days` Tage sind, werden die `minKeep` neuesten immer behalten. Beispiel: `olderThan(90, 5)` löscht bei 10 Snapshots maximal 5 — auch wenn alle 10 das Alterslimit überschreiten.

**Wann welche Policy?**

| Szenario | Empfehlung |
|---|---|
| Entwicklung / Tests: wenige, aktuelle Einträge behalten | `keepLatest(5)` |
| Produktion: rollierendes Zeitfenster mit Mindestbestand | `olderThan(180, 1)` |
| Regulatorisch: exaktes Ablaufdatum + Sicherheitspuffer | `olderThan(365, 3)` |

### `findChanges()` nach einem Cleanup

Nach einer Beförderung (UPDATE → INITIAL) liefert `javers.findChanges()` für die bereinigte Entity **drei Änderungstypen**:

| Typ | Bedeutung |
|---|---|
| `NewObject` | Entity "erstellt" am beförderten INITIAL-Snapshot |
| `InitialValueChange` | Eigenschaftswerte zum Zeitpunkt des beförderten INITIAL |
| `ValueChange` | Tatsächliche Änderungen zwischen aufeinanderfolgenden Snapshots |

Für die Filterung im eigenen Code: `ch.getClass() == ValueChange.class` schließt `InitialValueChange` (Subtyp) aus und liefert nur die echten Update-Diffs.

**Einschränkung — Lücken in der Snapshot-Kette:**  
Wenn ein mittlerer Snapshot gelöscht wurde, ohne zum Anker zu werden (z.B. bleiben v1, v3, v4 übrig, nachdem v2 gelöscht wurde), wirft `javers.findChanges()` eine `IllegalArgumentException` im `SnapshotDiffer`, weil Javers die Lücke nicht überbrücken kann. Diese Situation entsteht, wenn der Rettungsmechanismus einen anderen Snapshot als den an der Lückenposition sichert (er rettet den frühestmöglichen Anker, nicht den nächstjüngeren).

In diesem Fall bietet sich `javers.findSnapshots()` als Alternative an, um Zustände direkt zu vergleichen:

```java
List<CdoSnapshot> kette = javers.findSnapshots(QueryBuilder.byInstance(entity).build())
        .stream()
        .sorted(Comparator.comparingLong(CdoSnapshot::getVersion))
        .toList();

// Zustände direkt lesbar, Lücke sichtbar anhand der Versionsnummern:
kette.get(0).getState().getPropertyValue("city"); // Berlin   (v1 — Anker)
kette.get(1).getState().getPropertyValue("city"); // Hamburg  (v3 — v2/München gelöscht)
kette.get(2).getState().getPropertyValue("city"); // Frankfurt (v4)
```

Sobald nachfolgende Cleanup-Läufe den Anker zu INITIAL befördern (z.B. wird v4 zu INITIAL und ältere Snapshots werden entfernt), ist die Kette wieder lückenlos und `findChanges()` funktioniert korrekt.

### Scheduled-Cleanup einrichten

```java
@Component
public class JaversCleanupJob {

    private final JaversCleanupService cleanupService;

    @Scheduled(cron = "0 0 2 * * SUN")  // jeden Sonntag um 02:00 Uhr
    public void run() {
        CleanupResult result = cleanupService.cleanup(
            CleanupPolicy.olderThan(180, 1)
        );
        log.info("Javers cleanup: {}", result);
    }
}
```

### Wichtige Hinweise

- Der Service läuft in einer einzigen Transaktion pro `cleanup()`-Aufruf. Bei sehr großen Tabellen (Millionen Snapshots) sollte die Policy entsprechend eingeschränkt und der Job regelmäßig ausgeführt werden, um riesige Transaktionen zu vermeiden.
- Nach einem Cleanup sollte die Javers-Anwendung neu gestartet werden, falls ein interner Snapshot-Cache konfiguriert ist (`CachingJaversRepository`).
- `TERMINAL`-Snapshots (gelöschte Entities) werden nie zu INITIAL befördert.
- Die `jv_global_id`-Bereinigung am Ende jedes Cleanup-Laufs entfernt **alle** verwaisten Einträge — nicht nur die durch diesen Cleanup erzeugten, sondern auch solche aus externen Datenmanipulationen oder Teilmigrationen. Das ist ein nützlicher Nebeneffekt, der die Datenbank konsistent hält.

### Javers 7.11.x Schema

Die Spaltennamen haben sich gegenüber älteren Versionen geändert:

| Tabelle | PK-Spalte |
|---|---|
| `jv_snapshot` | `snapshot_pk` (früher: `id`) |
| `jv_commit` | `commit_pk` (früher: `id`) |
| `jv_global_id` | `global_id_pk` (früher: `id`) |
