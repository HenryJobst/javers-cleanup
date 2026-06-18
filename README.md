# javers-cleanup

Spring components for maintaining Javers audit tables — consistent removal of outdated snapshots and retroactive creation of missing INITIAL snapshots after a migration run with Javers auditing disabled.

Spring-Komponenten zur Pflege von Javers-Audit-Tabellen — konsistente Bereinigung veralteter Snapshots und retroaktive Erstellung fehlender INITIAL-Snapshots nach einer Migration ohne Javers-Auditing.

---

## Contents / Inhalt

- [Requirements](#requirements)
- [Project Structure](#project-structure)
- [Build & Test](#build--test)
- [Integration](#integration)
- [Documentation](#documentation)

---

## Requirements

| | |
|---|---|
| Java | 21+ |
| Spring Boot | 4.1.x |
| Javers | 7.11.x (`javers-spring-boot-starter-sql`) |
| Database | H2, PostgreSQL (and other SQL databases) |

The demo application runs with H2 in-memory. Integration tests against PostgreSQL require Docker (`mvn verify`).

Die Demo-Anwendung läuft mit H2 in-memory. Integrationstests gegen PostgreSQL erfordern Docker (`mvn verify`).

---

## Project Structure

```
src/main/java/de/tsboj/javerscleanup/
├── cleanup/
│   ├── JaversCleanupService.java    # snapshot cleanup
│   ├── CleanupPolicy.java           # count- or time-based policy
│   ├── CleanupResult.java           # result record
│   ├── JaversMigrationService.java  # retroactive snapshot creation
│   ├── MigrationResult.java         # result record
│   ├── SnapshotPromoter.java        # shared promotion helper (package-private)
│   └── SnapshotRow.java             # internal JDBC DTO (package-private)
└── demo/
    ├── Customer.java                # sample entity
    ├── CustomerRepository.java      # @JaversSpringDataAuditable
    ├── Order.java                   # sample entity with @ManyToOne reference
    ├── OrderRepository.java         # @JaversSpringDataAuditable
    └── DemoRunner.java              # CommandLineRunner demo
```

---

## Build & Test

```bash
# Quick test with H2 (no Docker required)
mvn test

# Full test suite including PostgreSQL via Testcontainers
mvn verify

# Start demo application
mvn spring-boot:run
```

---

## Integration

### Which files need to be copied?

Copy the entire `cleanup` package into the target application and adjust the package name.

**For cleanup only:**
```
CleanupPolicy.java
CleanupResult.java
JaversCleanupService.java
SnapshotPromoter.java     ← package-private, must stay in the same package
SnapshotRow.java          ← package-private, must stay in the same package
```

**For migration only:**
```
JaversMigrationService.java
MigrationResult.java
SnapshotPromoter.java     ← package-private, must stay in the same package
SnapshotRow.java          ← package-private, must stay in the same package
```

**For both:**
```
CleanupPolicy.java
CleanupResult.java
JaversCleanupService.java
JaversMigrationService.java
MigrationResult.java
SnapshotPromoter.java
SnapshotRow.java
```

### Additional Maven Dependencies

`JaversCleanupService` requires Jackson for JSON parsing of the `state` column (reference scan). In web-based Spring Boot applications Jackson is already available transitively; in pure JPA applications it must be added explicitly:

```xml
<!-- already present -->
<dependency>
    <groupId>org.javers</groupId>
    <artifactId>javers-spring-boot-starter-sql</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- only if jackson-databind is not already on the classpath -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### Note on package-private classes

`SnapshotRow` and `SnapshotPromoter` are `package-private`. Both services must therefore reside in the **same Java package**. Recommendation: place all classes in a dedicated package such as `com.example.javers.maintenance`.

`SnapshotRow` und `SnapshotPromoter` sind `package-private`. Beide Services müssen sich daher im **selben Java-Paket** befinden. Empfehlung: alle Klassen in ein dediziertes Paket wie `com.example.javers.maintenance` legen.

---

## Documentation

| Topic | Document |
|---|---|
| Snapshot Cleanup | [docs/cleanup.md](docs/cleanup.md) |
| Retroactive Snapshots after Migration | [docs/migration.md](docs/migration.md) |
