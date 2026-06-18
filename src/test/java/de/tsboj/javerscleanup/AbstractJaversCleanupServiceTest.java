package de.tsboj.javerscleanup;

import de.tsboj.javerscleanup.cleanup.CleanupPolicy;
import de.tsboj.javerscleanup.cleanup.CleanupResult;
import de.tsboj.javerscleanup.cleanup.JaversCleanupService;
import de.tsboj.javerscleanup.demo.*;
import de.tsboj.javerscleanup.demo.Tag;
import de.tsboj.javerscleanup.demo.TagRepository;
import org.javers.core.Javers;
import org.javers.core.diff.Change;
import org.javers.core.diff.changetype.InitialValueChange;
import org.javers.core.diff.changetype.ValueChange;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.SnapshotType;
import org.javers.repository.jql.QueryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@Transactional
abstract class AbstractJaversCleanupServiceTest {

    @Autowired CustomerRepository repo;
    @Autowired ContractRepository contractRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TagRepository tagRepo;
    @Autowired JaversCleanupService cleanupService;
    @Autowired Javers javers;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Behavior 0: empty snapshot table
    // -------------------------------------------------------------------------

    @Test
    void cleanup_on_empty_snapshot_table_returns_zero_result() {
        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.deletedSnapshots()).isEqualTo(0);
        assertThat(result.promotedSnapshots()).isEqualTo(0);
        assertThat(result.deletedCommits()).isEqualTo(0);
        assertThat(result.rescuedSnapshots()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Behavior 1: correct number of snapshots is deleted (count-based policy)
    // -------------------------------------------------------------------------

    @Test
    void keepLatest_deletesOldestSnapshots() {
        Customer c = createCustomerWithSnapshots(4);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.deletedSnapshots()).isEqualTo(2);
        assertThat(snapshotCount(c)).isEqualTo(2);
    }

    @Test
    void keepLatest_deletesNothing_whenFewerThanMinAvailable() {
        Customer c = createCustomerWithSnapshots(2);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(3));

        assertThat(result.deletedSnapshots()).isEqualTo(0);
        assertThat(snapshotCount(c)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Behavior 1b: time-based policy (olderThan)
    // -------------------------------------------------------------------------

    @Test
    void olderThan_deletesNothing_whenAllSnapshotsWithinRetentionPeriod() {
        Customer c = createCustomerWithSnapshots(4); // all committed now → within 7-day window

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.olderThan(7, 1));

        assertThat(result.deletedSnapshots()).isEqualTo(0);
        assertThat(snapshotCount(c)).isEqualTo(4);
    }

    @Test
    void olderThan_deletesOldSnapshots_andPromotesNewOldest() {
        // v1, v2 aged to 10 days ago; v3, v4 remain fresh.
        // olderThan(7, 1) must delete v1 and v2 (only they cross the cutoff),
        // leave v3 and v4 intact, and promote v3 (UPDATE) to INITIAL.
        Customer c = createCustomerWithSnapshots(4); // v1(I), v2(U), v3(U), v4(U)
        backdateCommitsByVersion(c, 2, 10); // age v1 and v2 to 10 days ago

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.olderThan(7, 1));

        assertThat(result.deletedSnapshots()).isEqualTo(2);
        assertThat(snapshotCount(c)).isEqualTo(2);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL); // v3 promoted
    }

    // -------------------------------------------------------------------------
    // Behavior 2: oldest remaining UPDATE snapshot is promoted to INITIAL
    // -------------------------------------------------------------------------

    @Test
    void cleanup_promotesOldestRemainingSnapshotToInitial() {
        Customer c = createCustomerWithSnapshots(4);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot oldestRemaining = findOldestSnapshot(c);
        assertThat(oldestRemaining.getType()).isEqualTo(SnapshotType.INITIAL);
    }

    @Test
    void cleanup_promotesOldestUpdate_whenOriginalInitialIsDeleted() {
        // v1(INITIAL) is deleted; v2(UPDATE) becomes oldest → promoted to INITIAL.
        // Verifies exactly 1 promotion and that the oldest remaining is INITIAL.
        Customer c = createCustomerWithSnapshots(3);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.promotedSnapshots()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    @Test
    void cleanup_does_not_promote_terminal_snapshot_to_initial() {
        // TERMINAL snapshots mark a deleted entity and must never become INITIAL.
        // The guard `"UPDATE".equals(type)` in the promotion logic must reject them.
        Customer c = repo.save(new Customer("Term", "term@test.de", "000", "Berlin")); // v1 INITIAL
        c.setCity("Munich"); c = repo.save(c); // v2 UPDATE
        repo.delete(c);                        // v3 TERMINAL

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.promotedSnapshots()).isEqualTo(0);
        List<CdoSnapshot> remaining = javers.findSnapshots(QueryBuilder.byInstance(c).build());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getType()).isEqualTo(SnapshotType.TERMINAL);
    }

    // -------------------------------------------------------------------------
    // Behavior 3: promoted snapshot contains ALL property names
    // -------------------------------------------------------------------------

    @Test
    void promotedInitialSnapshot_containsAllProperties() {
        Customer c = repo.save(new Customer("Anna Schmidt", "anna@test.de", "030-1", "Hamburg"));
        c.setEmail("anna.new@test.de"); c = repo.save(c);
        c.setPhone("030-2");            c = repo.save(c);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot promotedInitial = findOldestSnapshot(c);
        assertThat(promotedInitial.getType()).isEqualTo(SnapshotType.INITIAL);
        // Javers 7.x also tracks @Id fields as properties
        assertThat(promotedInitial.getChanged())
                .containsExactlyInAnyOrder("id", "name", "email", "phone", "city");
    }

    @Test
    void promotedInitialSnapshot_stateRemainsUnchanged() {
        Customer c = repo.save(new Customer("Bob Bauer", "bob@test.de", "040-1", "Bremen"));
        c.setCity("Kiel");       c = repo.save(c);
        c.setCity("Flensburg");  c = repo.save(c);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot promoted = findOldestSnapshot(c);
        assertThat(promoted.getState().getPropertyValue("city")).isEqualTo("Kiel");
        assertThat(promoted.getState().getPropertyValue("name")).isEqualTo("Bob Bauer");
    }

    // -------------------------------------------------------------------------
    // Behavior 4: orphaned commits are removed
    // -------------------------------------------------------------------------

    @Test
    void cleanup_removesOrphanedCommits() {
        Customer c = createCustomerWithSnapshots(4);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.deletedCommits()).isEqualTo(3);
    }

    @Test
    void cleanup_removes_orphaned_jv_global_id_when_no_snapshot_references_it() {
        // Create a Customer (generates jv_snapshot + jv_global_id), then manually
        // delete the snapshot to leave an orphaned jv_global_id entry.
        // cleanupOrphanedData() must remove it.
        Customer c = repo.save(new Customer("Orphan", "orphan@test.de", "000", "Berlin"));
        String typeName = Customer.class.getName();
        String localId  = String.valueOf(c.getId());

        // Bypass the cleanup service and remove the snapshot directly
        jdbc.update("""
                DELETE FROM jv_snapshot
                WHERE global_id_fk = (
                    SELECT global_id_pk FROM jv_global_id
                    WHERE type_name = ? AND local_id = ?
                )
                """, typeName, localId);

        cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jv_global_id WHERE type_name = ? AND local_id = ?",
                Integer.class, typeName, localId);
        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Behavior 5: multiple entities are cleaned up independently
    // -------------------------------------------------------------------------

    @Test
    void cleanup_handlesMultipleEntitiesIndependently() {
        Customer c1 = createCustomerWithSnapshots(3);
        Customer c2 = createCustomerWithSnapshots(5);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.deletedSnapshots()).isEqualTo(1 + 3);
        assertThat(snapshotCount(c1)).isEqualTo(2);
        assertThat(snapshotCount(c2)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Behavior 6: Value Objects (embedded entities with own jv_global_id entries)
    // -------------------------------------------------------------------------

    @Test
    void cleanup_promotesValueObjectSnapshotToInitial_whenOwnerInitialIsDeleted() {
        // Contract has an embedded ContractPeriod (Javers Value Object with own
        // jv_global_id entry, fragment="/period"). Cleanup must correctly promote
        // the VO snapshot using byValueObjectId(), not byInstanceId().
        Contract c = contractRepo.save(new Contract("Service Contract",
                new ContractPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))));
        c.setPeriod(new ContractPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31)));
        c = contractRepo.save(c); // UPDATE: period.endDate changed
        c.setPeriod(new ContractPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 12, 31)));
        c = contractRepo.save(c); // UPDATE: period.endDate changed again

        // keepLatest(2) deletes v1 (INITIAL) from both Contract and ContractPeriod VO
        // → oldest remaining snapshot (v2) must be promoted to INITIAL for both
        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.promotedSnapshots()).isGreaterThanOrEqualTo(1);

        // Verify Contract entity: oldest remaining snapshot is INITIAL
        List<CdoSnapshot> entitySnapshots = javers.findSnapshots(
                QueryBuilder.byInstance(c).build());
        assertThat(entitySnapshots.stream().min(Comparator.comparingLong(CdoSnapshot::getVersion)))
                .map(CdoSnapshot::getType)
                .hasValue(SnapshotType.INITIAL);

        // Verify ContractPeriod VO: oldest remaining snapshot is INITIAL
        // Javers 7.11.x stores the fragment without a leading slash (e.g. "period" not "/period")
        List<CdoSnapshot> voSnapshots = javers.findSnapshots(
                QueryBuilder.byValueObjectId(c.getId(), Contract.class, "period").build());
        assertThat(voSnapshots).isNotEmpty();
        assertThat(voSnapshots.stream().min(Comparator.comparingLong(CdoSnapshot::getVersion)))
                .map(CdoSnapshot::getType)
                .hasValue(SnapshotType.INITIAL);
    }

    // -------------------------------------------------------------------------
    // Behavior 7: cross-entity reference protection
    // -------------------------------------------------------------------------

    @Test
    void reference_protection_rescues_anchor_snapshot_when_referencing_snapshot_is_retained() {
        // Timeline: Customer.v1 → Order.v1 (refs Customer) → Customer.v2
        // keepLatest(1) would delete Customer.v1, but Order.v1 (retained) was committed
        // when Customer.v1 was the current state. Customer.v1 must be rescued.
        Customer customer = repo.save(new Customer("Alice", "alice@test.de", "030", "Berlin"));
        orderRepo.save(new Order("ORD-1", customer, "NEW"));
        customer.setCity("Hamburg");
        customer = repo.save(customer);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.rescuedSnapshots()).isEqualTo(1);
        assertThat(snapshotCount(customer)).isEqualTo(2); // v1 rescued + v2 kept
    }

    @Test
    void reference_protection_skips_rescue_when_retained_snapshot_already_covers_reference_date() {
        // Timeline: Customer.v1 → Customer.v2 → Order.v1 (refs Customer)
        // Order.v1 was committed AFTER Customer.v2, so Customer.v2 already covers the reference.
        Customer customer = repo.save(new Customer("Bob", "bob@test.de", "040", "Munich"));
        customer.setCity("Cologne");
        customer = repo.save(customer);
        orderRepo.save(new Order("ORD-2", customer, "NEW"));

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.rescuedSnapshots()).isEqualTo(0);
        assertThat(snapshotCount(customer)).isEqualTo(1);
    }

    @Test
    void reference_protection_skips_rescue_for_entity_not_referenced_by_any_retained_snapshot() {
        // Customer has 3 snapshots but no Order references it.
        // keepLatest(1) should delete 2 snapshots without any rescue.
        Customer customer = createCustomerWithSnapshots(3);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.rescuedSnapshots()).isEqualTo(0);
        assertThat(snapshotCount(customer)).isEqualTo(1);
    }

    @Test
    void reference_protection_uses_earliest_reference_date_when_multiple_snapshots_reference_same_entity() {
        // Customer.v1 → Order1.v1 → Order2.v1 (both ref Customer) → Customer.v2
        // Both orders are retained; the earlier one drives the rescue anchor date.
        Customer customer = repo.save(new Customer("Carol", "carol@test.de", "089", "Frankfurt"));
        orderRepo.save(new Order("ORD-3", customer, "NEW"));
        orderRepo.save(new Order("ORD-4", customer, "NEW"));
        customer.setCity("Stuttgart");
        customer = repo.save(customer);

        cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        // Customer.v1 must be rescued because both orders were committed before Customer.v2
        assertThat(snapshotCount(customer)).isEqualTo(2);
    }

    @Test
    void reference_protection_rescues_only_directly_referenced_entity_not_unrelated_ones() {
        // Customer1 (unreferenced) and Customer2 (referenced by Order) both have old snapshots.
        // Hybrid precision: only Customer2.v1 is rescued; Customer1.v1 is not (no reference).
        // This distinguishes the hybrid from Approach 1 (global anchor), which would also
        // rescue Customer1 if its oldest retained snapshot postdates Order's commit.
        Customer c1 = repo.save(new Customer("Dave", "dave@test.de", "030", "Berlin"));
        Customer c2 = repo.save(new Customer("Eve", "eve@test.de", "040", "Munich"));
        orderRepo.save(new Order("ORD-5", c2, "NEW")); // references c2, not c1
        c1.setCity("Hamburg");
        c1 = repo.save(c1);     // c1.v2 committed AFTER the Order
        c2.setCity("Dresden");
        c2 = repo.save(c2);     // c2.v2 committed AFTER the Order

        cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(snapshotCount(c1)).isEqualTo(1); // c1.v1 NOT rescued — unreferenced
        assertThat(snapshotCount(c2)).isEqualTo(2); // c2.v1 rescued — referenced by Order
    }

    @Test
    void reference_protection_promotes_rescued_update_snapshot_to_initial() {
        // Customer: v1 (INITIAL) → v2 (UPDATE) → Order (refs Customer) → v3 (UPDATE) → v4 (UPDATE)
        // keepLatest(2) deletes v1 and v2, keeping v3 and v4.
        // But Order was committed between v2 and v3, so v3 postdates the reference.
        // v2 must be rescued (latest Customer snapshot before the Order's commitDate).
        // v2 is an UPDATE snapshot and must be promoted to INITIAL.
        Customer customer = repo.save(new Customer("Frank", "frank@test.de", "069", "Frankfurt")); // v1
        customer.setCity("Wiesbaden"); customer = repo.save(customer);  // v2
        orderRepo.save(new Order("ORD-6", customer, "NEW"));            // Order between v2 and v3
        customer.setCity("Mainz");     customer = repo.save(customer);  // v3 (after Order)
        customer.setCity("Darmstadt"); customer = repo.save(customer);  // v4 (after Order)

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        // v1 deleted, v2 rescued+promoted, v3 and v4 kept → 3 snapshots
        assertThat(snapshotCount(customer)).isEqualTo(3);
        CdoSnapshot oldest = findOldestSnapshot(customer);
        assertThat(oldest.getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(oldest.getVersion()).isEqualTo(2L); // v2, not v1
    }

    @Test
    void reference_protection_handles_no_deletable_anchor_snapshot_gracefully() {
        // Edge case: all Customer snapshots postdate the Order (impossible in normal flow,
        // but ensures no exception if the rescue search finds no candidate.
        // We simulate it indirectly: Order references Customer, but keepLatest(1) retains
        // Customer.v1 anyway (only 1 snapshot → nothing to delete), so rescue is a no-op.
        Customer customer = repo.save(new Customer("Grace", "grace@test.de", "030", "Berlin"));
        orderRepo.save(new Order("ORD-7", customer, "PENDING"));

        assertThatNoException().isThrownBy(
                () -> cleanupService.cleanup(CleanupPolicy.keepLatest(1)));

        assertThat(snapshotCount(customer)).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Behavior 7b: reference protection extends to entity refs inside VO state
    // -------------------------------------------------------------------------

    @Test
    void reference_protection_rescues_anchor_snapshot_referenced_from_retained_vo_state() {
        // ContractPeriod (a Javers Value Object) has a @ManyToOne Customer reference.
        // Phase 2 must scan VO snapshot state — not only entity snapshots — to detect
        // this reference and rescue the Customer anchor snapshot via Phase 3.
        //
        // Timeline: customer.v1 → Contract+VO committed (VO refs customer.v1) → customer.v2
        // keepLatest(1) would delete customer.v1, but the retained VO snapshot was committed
        // when v1 was current → rescue required.
        Customer customer = repo.save(new Customer("VORef", "voref@test.de", "0", "Berlin")); // v1

        // Contract committed while customer is still at v1 — VO references customer.v1.
        contractRepo.save(new Contract("Ref-Contract",
                new ContractPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), customer)));

        customer.setCity("Hamburg"); customer = repo.save(customer);  // v2 (after Contract)

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.rescuedSnapshots())
                .as("customer.v1 referenced from retained VO snapshot must be rescued")
                .isEqualTo(1);
        assertThat(snapshotCount(customer)).isEqualTo(2); // v1 rescued + v2 kept
    }

    // -------------------------------------------------------------------------
    // Behavior 7c: reference protection works for entities with String @Id
    // -------------------------------------------------------------------------

    @Test
    void reference_protection_works_for_string_id_entity() {
        // Tag uses @Id String code. Javers serializes String IDs with surrounding JSON quotes
        // in jv_global_id.local_id (e.g. "\"PRIO-1\""). buildEntityRefLookup must strip those
        // quotes (normalizeLocalId) so the lookup key matches cdoIdNode.asText() from state JSON.
        // parseLocalId must do the same so that byInstanceId finds the Tag snapshot for promotion.
        //
        // Timeline: tag.v1 → Order committed (refs tag.v1) → tag.v2 → tag.v3
        Tag tag = tagRepo.save(new Tag("PRIO-1", "Priority"));                   // v1

        Customer customer = repo.save(new Customer("StrId", "strid@test.de", "0", "Berlin"));
        orderRepo.save(new Order("ORD-STR", customer, "NEW", tag));              // refs tag.v1

        tag.setLabel("Priority High");     tag = tagRepo.save(tag);              // v2
        tag.setLabel("Priority Critical"); tag = tagRepo.save(tag);              // v3

        // keepLatest(1): delete tag.v1 and v2, keep v3.
        // But the retained Order snapshot was committed when tag.v1 was current → rescue v1.
        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        // Phase 3 rescues tag.v1 (reference protection).
        // Phase 4 rescues tag.v2 to fill the version gap v1…v3 caused by the rescue,
        // ensuring javers.findChanges() does not encounter a broken chain.
        assertThat(result.rescuedSnapshots())
                .as("Tag (String @Id): v1 rescued for reference protection, v2 rescued to fill gap")
                .isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Behavior 8: Javers API consistency after cleanup
    // -------------------------------------------------------------------------

    @Test
    void findChanges_returns_correct_diff_between_promoted_initial_and_next_update() {
        // Setup: v1(INITIAL, city=Berlin) → v2(UPDATE, city=City-1) → v3(UPDATE, city=City-2)
        // keepLatest(2) deletes v1 and promotes v2 to INITIAL.
        //
        // findChanges() returns three change types for this chain:
        //   NewObject       — object "created" at the promoted INITIAL
        //   InitialValueChange — property values at the promoted INITIAL (one per property)
        //   ValueChange     — actual update diffs between consecutive snapshots
        //
        // We assert two invariants that together prove the promoted INITIAL is coherent:
        //   (a) The InitialValueChange for city reflects the promoted snapshot's state (City-1, not Berlin).
        //   (b) The ValueChange for city correctly diffs against that baseline (City-1 → City-2).
        Customer c = createCustomerWithSnapshots(3);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        List<Change> changes = javers.findChanges(QueryBuilder.byInstance(c).build());

        // (a) InitialValueChange: city=City-1 (the promoted INITIAL's state, not the deleted v1 value)
        List<Change> initialCityChanges = changes.stream()
                .filter(ch -> ch instanceof InitialValueChange vc && "city".equals(vc.getPropertyName()))
                .toList();
        assertThat(initialCityChanges).hasSize(1);
        assertThat(((ValueChange) initialCityChanges.get(0)).getRight()).isEqualTo("City-1");

        // (b) ValueChange: city diff City-1 → City-2 (v2 INITIAL → v3 UPDATE)
        List<Change> updateCityChanges = changes.stream()
                .filter(ch -> ch.getClass() == ValueChange.class
                        && "city".equals(((ValueChange) ch).getPropertyName()))
                .toList();
        assertThat(updateCityChanges).hasSize(1);
        ValueChange cityDiff = (ValueChange) updateCityChanges.get(0);
        assertThat(cityDiff.getLeft()).isEqualTo("City-1");
        assertThat(cityDiff.getRight()).isEqualTo("City-2");
    }

    @Test
    void subsequent_save_after_cleanup_diffs_only_against_promoted_initial_not_deleted_snapshots() {
        // Setup: v1(INITIAL), v2(UPDATE city=City-1), v3(UPDATE city=City-2)
        // keepLatest(1) deletes v1 and v2, promotes v3 to INITIAL.
        // A subsequent phone change (v4) must diff only against v3 — no stale city
        // ValueChanges from the deleted snapshots must appear.
        Customer c = createCustomerWithSnapshots(3);

        cleanupService.cleanup(CleanupPolicy.keepLatest(1)); // only v3 remains, promoted to INITIAL

        c.setPhone("999");
        c = repo.save(c); // v4: UPDATE, only phone changed relative to v3

        List<Change> changes = javers.findChanges(QueryBuilder.byInstance(c).build());

        // Filter to ValueChange (exact class, not InitialValueChange):
        // these are the real update diffs between consecutive snapshot states.
        List<Change> realUpdates = changes.stream()
                .filter(ch -> ch.getClass() == ValueChange.class)
                .toList();

        // Exactly 1 update diff: phone 000 → 999.
        // If the deleted snapshots were used as baseline, spurious city ValueChanges would appear.
        assertThat(realUpdates).hasSize(1);
        ValueChange phoneDiff = (ValueChange) realUpdates.get(0);
        assertThat(phoneDiff.getPropertyName()).isEqualTo("phone");
        assertThat(phoneDiff.getLeft()).isEqualTo("000");
        assertThat(phoneDiff.getRight()).isEqualTo("999");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /** Ages the commits of the first {@code maxVersion} snapshots for {@code c} to {@code daysAgo} days in the past. */
    private void backdateCommitsByVersion(Customer c, long maxVersion, int daysAgo) {
        jdbc.update("""
                UPDATE jv_commit SET commit_date = ?
                WHERE commit_pk IN (
                    SELECT s.commit_fk FROM jv_snapshot s
                    JOIN jv_global_id g ON s.global_id_fk = g.global_id_pk
                    WHERE g.type_name = ? AND g.local_id = ? AND s.version <= ?
                )
                """,
                LocalDateTime.now().minusDays(daysAgo),
                Customer.class.getName(), String.valueOf(c.getId()), maxVersion);
    }

    private Customer createCustomerWithSnapshots(int snapshotCount) {
        Customer c = repo.save(new Customer("Test User", "test@example.de", "000", "Berlin"));
        for (int i = 1; i < snapshotCount; i++) {
            c.setCity("City-" + i);
            c = repo.save(c);
        }
        return c;
    }

    private int snapshotCount(Object entity) {
        return javers.findSnapshots(QueryBuilder.byInstance(entity).build()).size();
    }

    private CdoSnapshot findOldestSnapshot(Customer c) {
        List<CdoSnapshot> snapshots = javers.findSnapshots(
                QueryBuilder.byInstance(c).build());
        return snapshots.stream()
                .min(Comparator.comparingLong(CdoSnapshot::getVersion))
                .orElseThrow();
    }
}
