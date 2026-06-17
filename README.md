# javers-cleanup

Spring-Komponenten zur Pflege von Javers-Audit-Tabellen — konsistente Bereinigung veralteter Snapshots und retroaktive Erstellung fehlender INITIAL-Snapshots nach einer Migration ohne Javers-Auditing.

Spring components for maintaining Javers audit tables — consistent removal of outdated snapshots and retroactive creation of missing INITIAL snapshots after a migration run with Javers auditing disabled.

---

## Inhalt / Contents

- [Voraussetzungen / Requirements](#voraussetzungen--requirements)
- [Projektstruktur / Project Structure](#projektstruktur--project-structure)
- [Build & Test](#build--test)
- [Integration in Zielanwendungen / Integration into Target Applications](#integration)
- [Dokumentation / Documentation](#dokumentation--documentation)

---

## Voraussetzungen / Requirements

| | |
|---|---|
| Java | 21+ |
| Spring Boot | 4.1.x |
| Javers | 7.11.x (`javers-spring-boot-starter-sql`) |
| Datenbank | H2, PostgreSQL (und andere SQL-Datenbanken) |

Die Demo-Anwendung läuft mit H2 in-memory. Integrationstests gegen PostgreSQL erfordern Docker (`mvn verify`).

The demo application runs with H2 in-memory. Integration tests against PostgreSQL require Docker (`mvn verify`).

---

## Projektstruktur / Project Structure

```
src/main/java/de/tsboj/javerscleanup/
├── cleanup/
│   ├── JaversCleanupService.java    # Bereinigung veralteter Snapshots
│   ├── CleanupPolicy.java           # count- oder zeitbasierte Policy
│   ├── CleanupResult.java           # Ergebnis-Record
│   ├── JaversMigrationService.java  # Retroaktive Snapshot-Erstellung
│   ├── MigrationResult.java         # Ergebnis-Record
│   └── SnapshotRow.java             # Internes JDBC-DTO (package-private)
└── demo/
    ├── Customer.java                # Beispiel-Entity
    ├── CustomerRepository.java      # @JaversSpringDataAuditable
    ├── Order.java                   # Beispiel-Entity mit @ManyToOne-Referenz
    ├── OrderRepository.java         # @JaversSpringDataAuditable
    └── DemoRunner.java              # CommandLineRunner-Demo
```

---

## Build & Test

```bash
# Schnelltest mit H2 (kein Docker erforderlich)
mvn test

# Vollständiger Test inkl. PostgreSQL via Testcontainers
mvn verify

# Demo-Anwendung starten
mvn spring-boot:run
```

---

## Integration

### Welche Dateien müssen übernommen werden? / Which files need to be copied?

Kopiere das gesamte `cleanup`-Paket in die Zielanwendung und passe den Package-Namen an.  
Copy the entire `cleanup` package into the target application and adjust the package name.

**Für Cleanup / For cleanup only:**
```
CleanupPolicy.java
CleanupResult.java
JaversCleanupService.java
SnapshotRow.java          ← package-private, muss im selben Paket bleiben
```

**Für Migration / For migration only:**
```
JaversMigrationService.java
MigrationResult.java
SnapshotRow.java          ← package-private, muss im selben Paket bleiben
```

**Für beides / For both:**
```
CleanupPolicy.java
CleanupResult.java
JaversCleanupService.java
JaversMigrationService.java
MigrationResult.java
SnapshotRow.java
```

### Zusätzliche Maven-Abhängigkeiten / Additional Maven Dependencies

`JaversCleanupService` benötigt Jackson für das JSON-Parsing der `state`-Spalte (Referenz-Scan). In web-basierten Spring-Boot-Anwendungen ist Jackson bereits transitiv vorhanden; in reinen JPA-Anwendungen muss es explizit ergänzt werden:

`JaversCleanupService` requires Jackson for JSON parsing of the `state` column (reference scan). In web-based Spring Boot applications Jackson is already available transitively; in pure JPA applications it must be added explicitly:

```xml
<!-- bereits vorhanden / already present -->
<dependency>
    <groupId>org.javers</groupId>
    <artifactId>javers-spring-boot-starter-sql</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- nur wenn jackson-databind nicht transitiv verfügbar ist / only if not already transitive -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### Hinweis zu `SnapshotRow` / Note on `SnapshotRow`

`SnapshotRow` ist `package-private` und dient als internes JDBC-DTO. Beide Services müssen sich im **selben Java-Paket** befinden. Empfehlung: alle Klassen in ein dediziertes Paket wie `com.example.javers.maintenance` legen.

`SnapshotRow` is `package-private` and serves as an internal JDBC DTO. Both services must reside in the **same Java package**. Recommendation: place all classes in a dedicated package such as `com.example.javers.maintenance`.

---

## Dokumentation / Documentation

| Thema / Topic | Dokument |
|---|---|
| Snapshot-Bereinigung / Snapshot Cleanup | [docs/cleanup.md](docs/cleanup.md) |
| Retroaktive Snapshots nach Migration / Retroactive Snapshots after Migration | [docs/migration.md](docs/migration.md) |
