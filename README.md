# javers-cleanup-spring-boot-starter

Spring Boot Starter für die Pflege von Javers-Audit-Tabellen — konsistente Bereinigung veralteter Snapshots und retroaktive Erstellung fehlender INITIAL-Snapshots nach einer Migration ohne Javers-Auditing.

→ [Deutsche Version](#deutsch)

---

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Build & Test](#build--test)
- [Documentation](#documentation)

---

## Requirements

| | |
|---|---|
| Java | 21+ |
| Spring Boot | 4.1.x |
| Javers | 7.11.x (`javers-spring-boot-starter-sql`) |
| Database | H2, PostgreSQL (and other SQL databases supported by Javers) |

---

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.henryjobst</groupId>
    <artifactId>javers-cleanup-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("io.github.henryjobst:javers-cleanup-spring-boot-starter:0.1.0")
```

`javers-spring-boot-starter-sql` and `spring-boot-starter-data-jpa` must already be on the classpath. The starter auto-configures `JaversCleanupService` and `JaversMigrationService` as soon as Javers and Spring JDBC are detected — no additional `@Bean` declarations required.

---

## Quick Start

```java
// Cleanup: keep only the last 30 snapshots per entity
@Autowired JaversCleanupService cleanupService;

CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(30));
// or: CleanupPolicy.olderThan(90, 1)  →  delete everything older than 90 days, keep at least 1

// Migration: retroactively create snapshots for entities saved without Javers
@Autowired JaversMigrationService migrationService;

MigrationResult result = migrationService.commitAll(entities, "data-migration");
// or: migrationService.commitAllAt(entities, "data-migration", Instant.parse("2025-03-15T02:00:00Z"))
```

---

## Project Structure

```
javers-cleanup/                          ← parent POM
├── javers-cleanup-spring-boot-starter/  ← library (published to Maven Central)
│   └── src/
│       ├── main/java/io/github/henryjobst/javerscleanup/
│       │   ├── JaversCleanupAutoConfiguration.java  ← @AutoConfiguration
│       │   ├── JaversCleanupService.java
│       │   ├── CleanupPolicy.java
│       │   ├── CleanupResult.java
│       │   ├── JaversMigrationService.java
│       │   ├── MigrationResult.java
│       │   ├── SnapshotPromoter.java   (package-private)
│       │   └── SnapshotRow.java        (package-private)
│       └── test/java/…/domain/         ← test fixtures (Customer, Order, …)
└── javers-cleanup-demo/                 ← demo application (not published)
```

---

## Build & Test

```bash
# Quick test with H2 (no Docker required)
mvn test -pl javers-cleanup-spring-boot-starter -am

# Full test suite including PostgreSQL via Testcontainers
mvn verify -pl javers-cleanup-spring-boot-starter -am

# Start demo application
mvn spring-boot:run -pl javers-cleanup-demo -am
```

---

## Documentation

| Topic | Document |
|---|---|
| Snapshot Cleanup | [docs/cleanup.md](docs/cleanup.md) |
| Retroactive Snapshots after Migration | [docs/migration.md](docs/migration.md) |

---

## Deutsch

Spring Boot Starter für die Pflege von Javers-Audit-Tabellen — konsistente Bereinigung veralteter Snapshots und retroaktive Erstellung fehlender INITIAL-Snapshots nach einer Migration ohne Javers-Auditing.

---

## Inhalt

- [Voraussetzungen](#voraussetzungen)
- [Installation](#installation-1)
- [Schnellstart](#schnellstart)
- [Projektstruktur](#projektstruktur)
- [Build & Test](#build--test-1)
- [Dokumentation](#dokumentation)

---

## Voraussetzungen

| | |
|---|---|
| Java | 21+ |
| Spring Boot | 4.1.x |
| Javers | 7.11.x (`javers-spring-boot-starter-sql`) |
| Datenbank | H2, PostgreSQL (und andere von Javers unterstützte SQL-Datenbanken) |

---

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.henryjobst</groupId>
    <artifactId>javers-cleanup-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("io.github.henryjobst:javers-cleanup-spring-boot-starter:0.1.0")
```

`javers-spring-boot-starter-sql` und `spring-boot-starter-data-jpa` müssen bereits im Classpath vorhanden sein. Der Starter konfiguriert `JaversCleanupService` und `JaversMigrationService` automatisch, sobald Javers und Spring JDBC erkannt werden — keine zusätzlichen `@Bean`-Definitionen notwendig.

---

## Schnellstart

```java
// Cleanup: nur die letzten 30 Snapshots pro Entity behalten
@Autowired JaversCleanupService cleanupService;

CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(30));
// oder: CleanupPolicy.olderThan(90, 1)  →  alles älter als 90 Tage löschen, mind. 1 behalten

// Migration: retroaktive Snapshots für ohne Javers gespeicherte Entitäten
@Autowired JaversMigrationService migrationService;

MigrationResult result = migrationService.commitAll(entities, "data-migration");
// oder: migrationService.commitAllAt(entities, "data-migration", Instant.parse("2025-03-15T02:00:00Z"))
```

---

## Projektstruktur

```
javers-cleanup/                          ← Eltern-POM
├── javers-cleanup-spring-boot-starter/  ← Bibliothek (auf Maven Central)
│   └── src/
│       ├── main/java/io/github/henryjobst/javerscleanup/
│       │   ├── JaversCleanupAutoConfiguration.java  ← @AutoConfiguration
│       │   ├── JaversCleanupService.java
│       │   ├── CleanupPolicy.java
│       │   ├── CleanupResult.java
│       │   ├── JaversMigrationService.java
│       │   ├── MigrationResult.java
│       │   ├── SnapshotPromoter.java   (package-private)
│       │   └── SnapshotRow.java        (package-private)
│       └── test/java/…/domain/         ← Test-Fixtures (Customer, Order, …)
└── javers-cleanup-demo/                 ← Demo-Anwendung (nicht veröffentlicht)
```

---

## Build & Test

```bash
# Schnelltest mit H2 (kein Docker erforderlich)
mvn test -pl javers-cleanup-spring-boot-starter -am

# Vollständiger Test inkl. PostgreSQL via Testcontainers
mvn verify -pl javers-cleanup-spring-boot-starter -am

# Demo-Anwendung starten
mvn spring-boot:run -pl javers-cleanup-demo -am
```

---

## Dokumentation

| Thema | Dokument |
|---|---|
| Snapshot-Bereinigung | [docs/cleanup.md](docs/cleanup.md) |
| Retroaktive Snapshots nach Migration | [docs/migration.md](docs/migration.md) |
