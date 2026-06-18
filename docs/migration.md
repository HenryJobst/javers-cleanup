# Retroaktive Javers-Snapshots nach Migration / Retroactive Javers Snapshots after Migration

---

## Deutsch

### Problem

Bei aufwendigen Datenmigrationen muss Javers-Auditing häufig deaktiviert werden, da jedes `save()` einen Snapshot-Vergleich, einen DB-Write und eine Sequenz-Abfrage auslöst. Bei Millionen von Datensätzen kann das die Migration um den Faktor 5–20 verlangsamen.

Nach der Migration fehlen Snapshots für alle betroffenen Entitäten. Ohne Nachbearbeitung entstehen folgende Probleme:

- **Neue Entitäten** (während Migration angelegt): kein Snapshot → bei der ersten Javers-aktivierten Änderung erstellt Javers ein `INITIAL` mit dem **post-change-Zustand**. Der Originalzustand geht verloren.
- **Bestehende Entitäten** (während Migration geändert): die letzte bekannte Snapshot-Kette endet vor der Migration. Die Änderungen sind für Javers unsichtbar.
- **Fehlende INITIAL-Snapshots in der Kette**: Wenn `INITIAL` bereits vor der Migration gelöscht wurde (z.B. durch einen früheren Cleanup ohne Beförderung), besteht die Kette nur aus `UPDATE`-Snapshots. Ein nachträglicher `javers.commit()` hängt einen weiteren `UPDATE` an — die Kette bleibt ohne `INITIAL`.

### Beobachtetes Fehlverhalten (getestet)

```
Ausgangszustand: INITIAL(v1), UPDATE(v2) → INITIAL(v1) wird gelöscht
Verbleibend: UPDATE(v2)

javers.commit() ohne Korrektur:
→ erstellt UPDATE(v3)
→ Kette: UPDATE(v2), UPDATE(v3)  ← kein INITIAL vorhanden!
```

Javers erstellt korrekt `INITIAL` wenn **gar keine** Snapshots existieren (auch nicht bei orphaned `jv_global_id`). Das Problem tritt nur auf, wenn noch `UPDATE`-Snapshots vorhanden sind, aber kein `INITIAL`.

### Korrekte Vorgehensweise

```
1. Migration ohne Javers durchführen
   ↓
2. migrationService.commitAll(entities, "data-migration")
   ↓ → neue Entitäten: INITIAL mit aktuellem Zustand
   ↓ → geänderte Entitäten: UPDATE mit Diff zum letzten Snapshot
   ↓ → fehlende INITIAL: automatische Korrektur des ältesten UPDATE
   ↓
3. Normale Weiterarbeit mit Javers
   → Änderungen erzeugen UPDATE-Snapshots mit korrektem Diff
```

**Kritisch:** Schritt 2 muss **vor** der ersten Javers-aktivierten Änderung erfolgen. Andernfalls erstellt Javers beim ersten `save()` ein `INITIAL` mit dem geänderten Zustand — der Originalzustand ist unwiederbringlich verloren.

### Lösung: `JaversMigrationService`

```java
@Autowired
JaversMigrationService migrationService;

// Retroaktive Snapshots mit aktuellem Zeitstempel
List<MyEntity> migratedEntities = entityRepo.findAll(); // alle migrierten Entitäten laden
MigrationResult result = migrationService.commitAll(migratedEntities, "data-migration");

// Mit historischem Migrationszeitstempel (erscheint zeitlich korrekt in der Audit-Historie)
Instant migrationTime = Instant.parse("2025-03-15T02:00:00Z");
MigrationResult result = migrationService.commitAllAt(
    migratedEntities, "data-migration", migrationTime
);
```

### Was `commitAll()` intern macht

1. Maximale `snapshot_pk` und `commit_pk` vor dem Lauf merken
2. `javers.commit(author, entity)` für jede Entität aufrufen
   - Javers prüft: existiert bereits ein Snapshot?
   - Nein → `INITIAL` mit aktuellem Zustand
   - Ja, Zustand geändert → `UPDATE` mit Diff
   - Ja, Zustand unverändert → kein neuer Snapshot
3. **Korrektur fehlender INITIAL:** alle neu erstellten Snapshots prüfen — falls der älteste Snapshot einer GlobalId `UPDATE` ist (kein vorheriger Snapshot in der gesamten Kette), wird er zu `INITIAL` befördert (analog zu `JaversCleanupService`). `newInitialSnapshots` und `newUpdateSnapshots` im Ergebnis zählen nur Entity-Snapshots; Value-Object-Snapshots (z.B. für `@Embeddable`-Felder) werden von Javers automatisch miterstellt, aber nicht separat ausgewiesen.
4. Optional: alle neuen `jv_commit`-Einträge auf den Migrationszeitstempel setzen

### Backdating (historischer Zeitstempel)

Javers bietet kein Public API zum Setzen eines historischen `commit_date`. `commitAllAt()` löst das durch direkte Anpassung der `jv_commit`-Tabelle nach dem Commit:

```sql
UPDATE jv_commit
SET commit_date = ?, commit_date_instant = ?
WHERE commit_pk > :maxBeforeRun
```

Beide Spalten werden aktualisiert (`commit_date` als SQL-Timestamp, `commit_date_instant` als ISO-8601-String).

### API

```java
// Mit aktuellem Zeitstempel
<T> MigrationResult commitAll(Collection<T> entities, String author)

// Mit historischem Migrationszeitstempel
<T> MigrationResult commitAllAt(Collection<T> entities, String author, Instant migrationTimestamp)

// Ergebnis
record MigrationResult(
    int newInitialSnapshots,   // neue Entity-INITIAL-Snapshots (VO-Snapshots nicht mitgezählt)
    int newUpdateSnapshots,    // neue Entity-UPDATE-Snapshots (VO-Snapshots nicht mitgezählt)
    int unchangedEntities,     // unveränderte Entitäten (kein neuer Snapshot)
    int correctedToInitial     // UPDATE→INITIAL-Korrekturen (fehlende INITIAL in Kette)
)
```

### Performance-Hinweis

`commitAll()` verarbeitet Entitäten sequenziell in einer Transaktion. Für sehr große Mengen empfiehlt es sich, in Batches zu arbeiten:

```java
// Beispiel: in Batches von 1000 verarbeiten
Pageable page = PageRequest.of(0, 1000);
Page<MyEntity> batch;
do {
    batch = entityRepo.findAll(page);
    migrationService.commitAll(batch.getContent(), "data-migration");
    page = page.next();
} while (batch.hasNext());
```

### Typischer Migrationsablauf

```java
@Component
public class DataMigration implements ApplicationRunner {

    @Autowired EntityManager em;
    @Autowired MyEntityRepository repo;
    @Autowired JaversMigrationService migrationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        // 1. Neue Daten einfügen (ohne Javers — direkt via EntityManager oder SQL)
        em.persist(new MyEntity(...));
        em.flush();

        // 2. Bestehende Daten ändern (ohne Javers)
        em.createQuery("UPDATE MyEntity e SET e.status = 'MIGRATED'").executeUpdate();
        em.flush();

        // 3. Retroaktive Snapshots erstellen
        List<MyEntity> affected = repo.findByMigrationBatch("2025-03");
        Instant migrationTime = Instant.parse("2025-03-15T02:00:00Z");
        MigrationResult result = migrationService.commitAllAt(
            affected, "migration-2025-03", migrationTime
        );
        log.info("Migration snapshots created: {}", result);

        // 4. Ab hier normaler Betrieb — Javers läuft wieder
    }
}
```

---

## English

### Problem

During complex data migrations, Javers auditing often needs to be disabled because every `save()` triggers a snapshot comparison, a DB write, and a sequence query. For millions of records this can slow the migration down by a factor of 5–20.

After the migration, snapshots are missing for all affected entities. Without post-processing this leads to:

- **New entities** (created during migration): no snapshot → at the first Javers-enabled change, Javers creates an `INITIAL` with the **post-change state**. The original state is permanently lost.
- **Existing entities** (changed during migration): the last known snapshot chain ends before the migration. The changes are invisible to Javers.
- **Missing INITIAL in the chain**: if `INITIAL` was already deleted before the migration (e.g. by a previous cleanup without promotion), the chain consists only of `UPDATE` snapshots. A subsequent `javers.commit()` appends another `UPDATE` — the chain remains without an `INITIAL`.

### Observed incorrect behavior (verified by test)

```
Starting state: INITIAL(v1), UPDATE(v2) → INITIAL(v1) is deleted
Remaining: UPDATE(v2)

javers.commit() without correction:
→ creates UPDATE(v3)
→ chain: UPDATE(v2), UPDATE(v3)  ← no INITIAL anywhere!
```

Javers correctly creates `INITIAL` when **no** snapshots exist at all (including with an orphaned `jv_global_id`). The problem only occurs when `UPDATE` snapshots are still present but no `INITIAL`.

### Correct workflow

```
1. Run migration without Javers
   ↓
2. migrationService.commitAll(entities, "data-migration")
   ↓ → new entities: INITIAL with current state
   ↓ → changed entities: UPDATE with diff against last snapshot
   ↓ → missing INITIAL: automatic correction of oldest UPDATE
   ↓
3. Resume normal operation with Javers
   → changes produce UPDATE snapshots with correct diffs
```

**Critical:** step 2 must happen **before** the first Javers-enabled change. Otherwise Javers creates an `INITIAL` at the first `save()` with the changed state — the original state is permanently lost.

### Solution: `JaversMigrationService`

```java
@Autowired
JaversMigrationService migrationService;

// Retroactive snapshots with current timestamp
List<MyEntity> migratedEntities = entityRepo.findAll(); // load all migrated entities
MigrationResult result = migrationService.commitAll(migratedEntities, "data-migration");

// With historical migration timestamp (appears chronologically correct in audit history)
Instant migrationTime = Instant.parse("2025-03-15T02:00:00Z");
MigrationResult result = migrationService.commitAllAt(
    migratedEntities, "data-migration", migrationTime
);
```

### What `commitAll()` does internally

1. Record the maximum `snapshot_pk` and `commit_pk` before the run
2. Call `javers.commit(author, entity)` for each entity
   - Javers checks: does a snapshot already exist?
   - No → `INITIAL` with current state
   - Yes, state changed → `UPDATE` with diff
   - Yes, state unchanged → no new snapshot
3. **Correction for missing INITIAL:** inspect all newly created snapshots — if the oldest snapshot of a GlobalId is `UPDATE` (no preceding snapshot exists in the entire chain), promote it to `INITIAL` (same mechanism as `JaversCleanupService`). `newInitialSnapshots` and `newUpdateSnapshots` in the result count entity-level snapshots only; Value Object snapshots (e.g. for `@Embeddable` fields) are created automatically by Javers but are not reported separately.
4. Optionally: set all new `jv_commit` entries to the migration timestamp

### Backdating (historical timestamp)

Javers provides no public API for setting a historical `commit_date`. `commitAllAt()` solves this by directly updating the `jv_commit` table after the commit:

```sql
UPDATE jv_commit
SET commit_date = ?, commit_date_instant = ?
WHERE commit_pk > :maxBeforeRun
```

Both columns are updated (`commit_date` as SQL timestamp, `commit_date_instant` as ISO-8601 string).

### API

```java
// With current timestamp
<T> MigrationResult commitAll(Collection<T> entities, String author)

// With historical migration timestamp
<T> MigrationResult commitAllAt(Collection<T> entities, String author, Instant migrationTimestamp)

// Result
record MigrationResult(
    int newInitialSnapshots,   // new entity-level INITIAL snapshots (VO snapshots not counted)
    int newUpdateSnapshots,    // new entity-level UPDATE snapshots (VO snapshots not counted)
    int unchangedEntities,     // unchanged entities (no new snapshot created)
    int correctedToInitial     // UPDATE→INITIAL corrections (missing INITIAL in chain)
)
```

### Performance note

`commitAll()` processes entities sequentially in one transaction. For very large datasets, process in batches:

```java
// Example: process in batches of 1000
Pageable page = PageRequest.of(0, 1000);
Page<MyEntity> batch;
do {
    batch = entityRepo.findAll(page);
    migrationService.commitAll(batch.getContent(), "data-migration");
    page = page.next();
} while (batch.hasNext());
```

### Typical migration flow

```java
@Component
public class DataMigration implements ApplicationRunner {

    @Autowired EntityManager em;
    @Autowired MyEntityRepository repo;
    @Autowired JaversMigrationService migrationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        // 1. Insert new data (without Javers — directly via EntityManager or SQL)
        em.persist(new MyEntity(...));
        em.flush();

        // 2. Modify existing data (without Javers)
        em.createQuery("UPDATE MyEntity e SET e.status = 'MIGRATED'").executeUpdate();
        em.flush();

        // 3. Create retroactive snapshots
        List<MyEntity> affected = repo.findByMigrationBatch("2025-03");
        Instant migrationTime = Instant.parse("2025-03-15T02:00:00Z");
        MigrationResult result = migrationService.commitAllAt(
            affected, "migration-2025-03", migrationTime
        );
        log.info("Migration snapshots created: {}", result);

        // 4. From here on, normal operation — Javers is active again
    }
}
```
