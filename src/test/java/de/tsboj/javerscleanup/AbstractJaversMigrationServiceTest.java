package de.tsboj.javerscleanup;

import de.tsboj.javerscleanup.cleanup.JaversMigrationService;
import de.tsboj.javerscleanup.cleanup.MigrationResult;
import de.tsboj.javerscleanup.demo.Customer;
import de.tsboj.javerscleanup.demo.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.javers.core.Javers;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.SnapshotType;
import org.javers.repository.jql.QueryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
abstract class AbstractJaversMigrationServiceTest {

    @Autowired CustomerRepository repo;
    @Autowired JaversMigrationService migrationService;
    @Autowired Javers javers;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    // -------------------------------------------------------------------------
    // Dokumentation: Javers-Rohverhalten mit orphaned global_id
    // -------------------------------------------------------------------------

    @Test
    void javersRohverhalten_erstelltInitialSnapshot_wennGlobalIdExistiertAberKeineSnapshots() {
        // Dokumentiert: Javers 7.11.x erstellt korrekt INITIAL wenn jv_global_id
        // zwar einen Eintrag hat, aber jv_snapshot leer ist (kein Bug in diesem Fall).
        Customer c = repo.save(new Customer("Raw", "raw@test.de", "000", "Berlin"));
        long globalIdFk = queryGlobalIdFk(c);
        jdbc.update("DELETE FROM jv_snapshot WHERE global_id_fk = ?", globalIdFk);
        assertThat(snapshotCount(c)).isEqualTo(0);

        javers.commit("raw", c);

        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    @Test
    void javersRohverhalten_erstelltUpdateOhneVorherigenInitial_wennInitialGeloeschtAberUpdateBleibt() {
        // Dokumentiert den echten Problemfall: INITIAL-Snapshot wurde entfernt
        // (z.B. manuell, oder durch Cleanup ohne Beförderung), danach kommt ein neuer
        // Commit → Javers erstellt UPDATE, obwohl kein INITIAL in der Kette ist.
        Customer c = repo.save(new Customer("Raw2", "raw2@test.de", "000", "Berlin"));
        c.setCity("München"); c = repo.save(c); // INITIAL(v1), UPDATE(v2)

        // Nur INITIAL löschen, UPDATE(v2) bleibt
        long initialPk = findOldestSnapshotPk(c);
        jdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk = ?", initialPk);
        assertThat(findOldestSnapshot(c).getType())
                .as("Ausgangszustand: nur UPDATE übrig, kein INITIAL")
                .isEqualTo(SnapshotType.UPDATE);

        // Weitere Änderung und direkter Javers-Commit (ohne unsere Korrektur)
        c.setCity("Hamburg");
        em.merge(c); em.flush();
        javers.commit("raw", c); // erstellt UPDATE(v3)

        // Kette ist jetzt: UPDATE(v2), UPDATE(v3) — kein INITIAL!
        assertThat(findOldestSnapshot(c).getType())
                .as("Javers Rohverhalten: ältester Snapshot bleibt UPDATE — fehlerhafte Kette")
                .isEqualTo(SnapshotType.UPDATE);
    }

    // -------------------------------------------------------------------------
    // Verhalten 1: Neue Entität (keinerlei Javers-History) → INITIAL
    // -------------------------------------------------------------------------

    @Test
    void commitAll_erstelltInitialSnapshot_fuerNeueEntitaet() {
        Customer c = saveWithoutAuditing("Neu", "neu@test.de", "000", "Berlin");
        assertThat(snapshotCount(c)).isEqualTo(0);

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.newInitialSnapshots()).isEqualTo(1);
        assertThat(result.newUpdateSnapshots()).isEqualTo(0);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    // -------------------------------------------------------------------------
    // Verhalten 2 (kritisch): INITIAL fehlt, nur UPDATE-Kette vorhanden
    // → commitAll muss den ältesten UPDATE zu INITIAL befördern
    // -------------------------------------------------------------------------

    @Test
    void commitAll_befoerdertAeltestenUpdateZuInitial_wennKeinInitialInKette() {
        Customer c = repo.save(new Customer("Alt", "alt@test.de", "000", "Berlin"));
        c.setCity("München"); c = repo.save(c); // INITIAL(v1), UPDATE(v2)

        // INITIAL löschen → Kette hat nur UPDATE(v2), kein INITIAL
        jdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk = ?", findOldestSnapshotPk(c));
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);

        // Migration (kein Auditing)
        c.setCity("Hamburg");
        em.merge(c); em.flush();

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        // JaversMigrationService muss erkennen und korrigieren
        assertThat(result.correctedToInitial()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findOldestSnapshot(c).getChanged())
                .containsExactlyInAnyOrder("id", "name", "email", "phone", "city");
        // Neuester Snapshot ist UPDATE mit korrektem Diff
        assertThat(findNewestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);
        assertThat(findNewestSnapshot(c).getChanged()).containsExactlyInAnyOrder("city");
    }

    // -------------------------------------------------------------------------
    // Verhalten 3: Bestehende Entität geändert, INITIAL intakt → UPDATE mit Diff
    // -------------------------------------------------------------------------

    @Test
    void commitAll_erstelltUpdateSnapshot_fuerBestehendeGeaenderteEntitaet() {
        Customer c = repo.save(new Customer("Bestehend", "b@test.de", "000", "Berlin"));

        c.setCity("Frankfurt");
        em.merge(c); em.flush();

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.newUpdateSnapshots()).isEqualTo(1);
        assertThat(result.correctedToInitial()).isEqualTo(0);
        assertThat(findNewestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);
        assertThat(findNewestSnapshot(c).getChanged()).containsExactlyInAnyOrder("city");
    }

    // -------------------------------------------------------------------------
    // Verhalten 4: Unveränderte Entität → kein neuer Snapshot
    // -------------------------------------------------------------------------

    @Test
    void commitAll_erstelltKeinenSnapshot_fuerUnveraenderteEntitaet() {
        Customer c = repo.save(new Customer("Gleich", "g@test.de", "000", "Berlin"));

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.unchangedEntities()).isEqualTo(1);
        assertThat(snapshotCount(c)).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Verhalten 5: Vollständiger Migrationsablauf
    // Initiales Einfügen OHNE Javers → retroaktiver Commit → Änderungen MIT Javers
    // -------------------------------------------------------------------------

    @Test
    void vollstaendigerAblauf_insertOhneJavers_commitAll_dannAendernMitJavers() {
        // Schritt 1: Daten initial OHNE Javers einfügen (Migration mit deaktiviertem Auditing)
        Customer c = saveWithoutAuditing("Migration", "mig@test.de", "000", "Berlin");
        assertThat(snapshotCount(c)).as("kein Snapshot nach Insert ohne Javers").isEqualTo(0);

        // Schritt 2: retroaktiver INITIAL-Snapshot für den Ausgangszustand
        MigrationResult result = migrationService.commitAll(List.of(c), "data-migration");
        assertThat(result.newInitialSnapshots()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findOldestSnapshot(c).getState().getPropertyValue("city")).isEqualTo("Berlin");

        // Schritt 3: normale Änderung MIT Javers (normaler Betrieb nach Migration)
        c.setCity("München");
        c = repo.save(c);

        // Kette muss vollständig sein: INITIAL (Ausgangszustand) → UPDATE (Änderung)
        assertThat(snapshotCount(c)).isEqualTo(2);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findNewestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);
        assertThat(findNewestSnapshot(c).getChanged()).containsExactlyInAnyOrder("city");
    }

    @Test
    void ohneRETroaktivenCommit_hatInitialSnapshotFalschemZustand() {
        // Anti-Pattern: Änderung mit Javers OHNE vorherigen retroaktiven commitAll.
        // Javers erstellt zwar INITIAL (da kein Snapshot existiert), aber dieser
        // enthält den POST-Change-Zustand — der Original-Einfüge-Zustand geht verloren.
        Customer c = saveWithoutAuditing("Verloren", "v@test.de", "000", "Berlin");

        c.setCity("München");
        c = repo.save(c); // Javers: kein Vorsnapshot → erstellt INITIAL mit München

        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        // INITIAL hat den geänderten Zustand (München), nicht den Originalzustand (Berlin)
        assertThat(findOldestSnapshot(c).getState().getPropertyValue("city")).isEqualTo("München");
        // → Lösung: migrationService.commitAll() VOR der ersten Javers-Änderung aufrufen
    }

    // -------------------------------------------------------------------------
    // Verhalten 6: Backdating — historischer Migrationszeitstempel
    // -------------------------------------------------------------------------

    @Test
    void commitAllAt_setztHistorischenZeitstempel() {
        Customer c = saveWithoutAuditing("Historisch", "h@test.de", "000", "Berlin");
        Instant migrationTime = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        migrationService.commitAllAt(List.of(c), "migration", migrationTime);

        assertThat(snapshotCount(c)).isEqualTo(1);

        Instant storedDate = jdbc.queryForObject("""
                SELECT c.commit_date
                FROM jv_commit c
                JOIN jv_snapshot s ON s.commit_fk = c.commit_pk
                JOIN jv_global_id g ON s.global_id_fk = g.global_id_pk
                WHERE g.local_id = ? AND g.type_name = ?
                """,
                (rs, i) -> rs.getTimestamp("commit_date").toInstant(),
                String.valueOf(c.getId()),
                Customer.class.getName());

        assertThat(storedDate).isEqualTo(migrationTime);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private Customer saveWithoutAuditing(String name, String email, String phone, String city) {
        Customer c = new Customer(name, email, phone, city);
        em.persist(c);
        em.flush();
        return c;
    }

    private long queryGlobalIdFk(Customer c) {
        return jdbc.queryForObject(
                "SELECT global_id_pk FROM jv_global_id WHERE local_id = ? AND type_name = ?",
                Long.class, String.valueOf(c.getId()), Customer.class.getName());
    }

    private long findOldestSnapshotPk(Customer c) {
        return javers.findSnapshots(QueryBuilder.byInstance(c).build()).stream()
                .min(Comparator.comparingLong(CdoSnapshot::getVersion))
                .map(s -> jdbc.queryForObject(
                        "SELECT snapshot_pk FROM jv_snapshot WHERE global_id_fk = ? AND version = ?",
                        Long.class, queryGlobalIdFk(c), s.getVersion()))
                .orElseThrow();
    }

    private int snapshotCount(Customer c) {
        return javers.findSnapshots(QueryBuilder.byInstance(c).build()).size();
    }

    private CdoSnapshot findOldestSnapshot(Customer c) {
        return javers.findSnapshots(QueryBuilder.byInstance(c).build()).stream()
                .min(Comparator.comparingLong(CdoSnapshot::getVersion)).orElseThrow();
    }

    private CdoSnapshot findNewestSnapshot(Customer c) {
        return javers.findSnapshots(QueryBuilder.byInstance(c).build()).stream()
                .max(Comparator.comparingLong(CdoSnapshot::getVersion)).orElseThrow();
    }
}
