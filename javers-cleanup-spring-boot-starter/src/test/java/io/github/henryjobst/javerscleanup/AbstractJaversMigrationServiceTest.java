package io.github.henryjobst.javerscleanup;

import io.github.henryjobst.javerscleanup.domain.*;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
abstract class AbstractJaversMigrationServiceTest {

    @Autowired CustomerRepository repo;
    @Autowired ContractRepository contractRepo;
    @Autowired JaversMigrationService migrationService;
    @Autowired Javers javers;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    // -------------------------------------------------------------------------
    // Documentation: Javers raw behavior with orphaned global_id entries
    // -------------------------------------------------------------------------

    @Test
    void javersDefaultBehavior_createsInitialSnapshot_whenGlobalIdExistsButNoSnapshots() {
        // Documents: Javers 7.11.x correctly creates INITIAL when jv_global_id has an
        // entry but jv_snapshot is empty for that entity (no bug in this case).
        Customer c = repo.save(new Customer("Raw", "raw@test.de", "000", "Berlin"));
        long globalIdFk = queryGlobalIdFk(c);
        jdbc.update("DELETE FROM jv_snapshot WHERE global_id_fk = ?", globalIdFk);
        assertThat(snapshotCount(c)).isEqualTo(0);

        javers.commit("raw", c);

        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    @Test
    void javersDefaultBehavior_createsUpdateWithoutPriorInitial_whenInitialDeletedButUpdateRemains() {
        // Documents the actual problem case: the INITIAL snapshot was removed (e.g.
        // manually or by a cleanup without promotion) while UPDATE snapshots remain.
        // A subsequent javers.commit() appends another UPDATE — the chain has no INITIAL.
        Customer c = repo.save(new Customer("Raw2", "raw2@test.de", "000", "Berlin"));
        c.setCity("Munich"); c = repo.save(c); // INITIAL(v1), UPDATE(v2)

        // Delete only INITIAL, keep UPDATE(v2)
        long initialPk = findOldestSnapshotPk(c);
        jdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk = ?", initialPk);
        assertThat(findOldestSnapshot(c).getType())
                .as("starting state: only UPDATE remains, no INITIAL")
                .isEqualTo(SnapshotType.UPDATE);

        // Further change and direct Javers commit (without our correction)
        c.setCity("Hamburg");
        em.merge(c); em.flush();
        javers.commit("raw", c); // creates UPDATE(v3)

        // Chain is now: UPDATE(v2), UPDATE(v3) — no INITIAL
        assertThat(findOldestSnapshot(c).getType())
                .as("Javers raw behavior: oldest snapshot remains UPDATE — broken chain")
                .isEqualTo(SnapshotType.UPDATE);
    }

    // -------------------------------------------------------------------------
    // Behavior 1: new entity (no Javers history at all) → INITIAL
    // -------------------------------------------------------------------------

    @Test
    void commitAll_createsInitialSnapshot_forNewEntity() {
        Customer c = saveWithoutAuditing("New", "new@test.de", "000", "Berlin");
        assertThat(snapshotCount(c)).isEqualTo(0);

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.newInitialSnapshots()).isEqualTo(1);
        assertThat(result.newUpdateSnapshots()).isEqualTo(0);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    // -------------------------------------------------------------------------
    // Behavior 2 (critical): INITIAL missing, chain contains only UPDATEs
    // → commitAll must promote the oldest UPDATE to INITIAL
    // -------------------------------------------------------------------------

    @Test
    void commitAll_promotesOldestUpdateToInitial_whenNoInitialInChain() {
        Customer c = repo.save(new Customer("Existing", "e@test.de", "000", "Berlin"));
        c.setCity("Munich"); c = repo.save(c); // INITIAL(v1), UPDATE(v2)

        // Delete INITIAL → chain has only UPDATE(v2), no INITIAL
        jdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk = ?", findOldestSnapshotPk(c));
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);

        // Migration change (no auditing)
        c.setCity("Hamburg");
        em.merge(c); em.flush();

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.correctedToInitial()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findOldestSnapshot(c).getChanged())
                .containsExactlyInAnyOrder("id", "name", "email", "phone", "city");
        // newest snapshot is UPDATE with correct diff
        assertThat(findNewestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);
        assertThat(findNewestSnapshot(c).getChanged()).containsExactlyInAnyOrder("city");
    }

    // -------------------------------------------------------------------------
    // Behavior 3: existing entity changed, INITIAL intact → UPDATE with diff
    // -------------------------------------------------------------------------

    @Test
    void commitAll_createsUpdateSnapshot_forExistingChangedEntity() {
        Customer c = repo.save(new Customer("Existing", "b@test.de", "000", "Berlin"));

        c.setCity("Frankfurt");
        em.merge(c); em.flush();

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.newUpdateSnapshots()).isEqualTo(1);
        assertThat(result.correctedToInitial()).isEqualTo(0);
        assertThat(findNewestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);
        assertThat(findNewestSnapshot(c).getChanged()).containsExactlyInAnyOrder("city");
    }

    // -------------------------------------------------------------------------
    // Behavior 4: unchanged entity → no new snapshot
    // -------------------------------------------------------------------------

    @Test
    void commitAll_createsNoSnapshot_forUnchangedEntity() {
        Customer c = repo.save(new Customer("Unchanged", "u@test.de", "000", "Berlin"));

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.unchangedEntities()).isEqualTo(1);
        assertThat(snapshotCount(c)).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Behavior 5: complete migration flow
    // Insert WITHOUT Javers → retroactive commitAll → modify WITH Javers
    // -------------------------------------------------------------------------

    @Test
    void fullMigrationFlow_insertWithoutJavers_commitAll_thenModifyWithJavers() {
        // Step 1: insert data WITHOUT Javers (migration with auditing disabled)
        Customer c = saveWithoutAuditing("Migration", "mig@test.de", "000", "Berlin");
        assertThat(snapshotCount(c)).as("no snapshot after insert without Javers").isEqualTo(0);

        // Step 2: retroactive INITIAL snapshot for the original state
        MigrationResult result = migrationService.commitAll(List.of(c), "data-migration");
        assertThat(result.newInitialSnapshots()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findOldestSnapshot(c).getState().getPropertyValue("city")).isEqualTo("Berlin");

        // Step 3: normal change WITH Javers (regular operation after migration)
        c.setCity("Munich");
        c = repo.save(c);

        // Chain must be complete: INITIAL (original state) → UPDATE (change)
        assertThat(snapshotCount(c)).isEqualTo(2);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findNewestSnapshot(c).getType()).isEqualTo(SnapshotType.UPDATE);
        assertThat(findNewestSnapshot(c).getChanged()).containsExactlyInAnyOrder("city");
    }

    @Test
    void withoutRetroactiveCommit_initialSnapshotHasWrongState() {
        // Anti-pattern: changing an entity with Javers WITHOUT calling commitAll first.
        // Javers creates INITIAL (no prior snapshot exists) but it captures the
        // POST-change state — the original insertion state is permanently lost.
        Customer c = saveWithoutAuditing("Lost", "lost@test.de", "000", "Berlin");

        c.setCity("Munich");
        c = repo.save(c); // Javers: no prior snapshot → creates INITIAL with Munich

        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
        // INITIAL has the changed state (Munich), not the original state (Berlin)
        assertThat(findOldestSnapshot(c).getState().getPropertyValue("city")).isEqualTo("Munich");
        // → solution: call migrationService.commitAll() BEFORE the first Javers-enabled change
    }

    // -------------------------------------------------------------------------
    // Behavior 5b: unchangedEntities count is correct when VOs are present
    // -------------------------------------------------------------------------

    @Test
    void commitAll_unchangedEntities_count_is_correct_when_some_entities_have_value_objects() {
        // 'unchanged' is already audited and its state will not change.
        Contract unchanged = contractRepo.save(new Contract("Existing",
                new ContractPeriod(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))));

        // Two contracts not yet audited, each with an embedded VO.
        Contract newC1 = new Contract("New-1",
                new ContractPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)));
        em.persist(newC1); em.flush();
        Contract newC2 = new Contract("New-2",
                new ContractPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)));
        em.persist(newC2); em.flush();

        // javers.commit() creates 2 INITIAL snapshots per new contract (entity + VO).
        // buildResult must count only entity snapshots so that unchangedEntities = 1,
        // not 0 (which was the bug when VO snapshots inflated the initial count).
        MigrationResult result = migrationService.commitAll(
                List.of(newC1, newC2, unchanged), "migration");

        assertThat(result.newInitialSnapshots()).isEqualTo(2); // newC1, newC2 (VOs excluded)
        assertThat(result.newUpdateSnapshots()).isEqualTo(0);
        assertThat(result.unchangedEntities()).isEqualTo(1);   // 'unchanged' had no diff
    }

    // -------------------------------------------------------------------------
    // Behavior 6: Value Objects (embedded entities with own jv_global_id entries)
    // -------------------------------------------------------------------------

    @Test
    void commitAll_createsInitialSnapshot_forNewEntityWithValueObject() {
        // Entity with embedded VO (ContractPeriod) saved without auditing.
        // javers.commit() must create INITIAL for both the entity and its VO.
        Contract c = new Contract("New Contract",
                new ContractPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)));
        em.persist(c);
        em.flush();

        MigrationResult result = migrationService.commitAll(List.of(c), "migration");

        assertThat(result.newInitialSnapshots()).isGreaterThanOrEqualTo(1);

        // Entity snapshot
        List<CdoSnapshot> entitySnapshots = javers.findSnapshots(
                QueryBuilder.byInstance(c).build());
        assertThat(entitySnapshots).isNotEmpty();
        assertThat(entitySnapshots.getFirst().getType()).isEqualTo(SnapshotType.INITIAL);

        // Value Object snapshot — Javers 7.11.x stores fragment without leading slash
        List<CdoSnapshot> voSnapshots = javers.findSnapshots(
                QueryBuilder.byValueObjectId(c.getId(), Contract.class, "period").build());
        assertThat(voSnapshots).isNotEmpty();
        assertThat(voSnapshots.getFirst().getType()).isEqualTo(SnapshotType.INITIAL);
    }

    // -------------------------------------------------------------------------
    // Behavior 7: backdating — historical migration timestamp
    // -------------------------------------------------------------------------

    @Test
    void commitAllAt_setsHistoricalTimestamp() {
        Customer c = saveWithoutAuditing("Historical", "h@test.de", "000", "Berlin");
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
    // Helper methods
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
