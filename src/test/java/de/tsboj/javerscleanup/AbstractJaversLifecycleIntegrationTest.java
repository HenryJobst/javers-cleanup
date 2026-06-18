package de.tsboj.javerscleanup;

import de.tsboj.javerscleanup.cleanup.CleanupPolicy;
import de.tsboj.javerscleanup.cleanup.CleanupResult;
import de.tsboj.javerscleanup.cleanup.JaversCleanupService;
import de.tsboj.javerscleanup.demo.*;
import org.javers.core.Javers;
import org.javers.core.diff.Change;
import org.javers.core.diff.changetype.ValueChange;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.SnapshotType;
import org.javers.repository.jql.QueryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-step lifecycle integration test: several linked entities, multiple modifications
 * across those entities, two cleanup runs, and Javers audit-history assertions between
 * each cleanup.
 *
 * <h2>Entity graph under test</h2>
 * <pre>
 *   Customer  ←── Order            (@ManyToOne, Long @Id)
 *   Customer  ←── ContractPeriod   (@ManyToOne inside @Embeddable VO, tests VO ref scanning)
 *   Tag       ←── Order            (@ManyToOne, String @Id — tests key normalisation)
 *   Contract   ──► ContractPeriod  (@Embedded VO)
 * </pre>
 *
 * <h2>Timeline</h2>
 * <pre>
 *   Phase 1   Build history
 *               Customer: v1(Berlin)→v2(Munich)→v3(Hamburg)→v4(Frankfurt)
 *               Tag "VIP": v1
 *               Order "ORD-001": v1(PENDING)  — committed while Customer=v1, Tag=v1
 *               Contract "SLA-2024" + VO: v1  — VO refs Customer=v4
 *
 *   Cleanup 1  keepLatest(2)
 *               Customer: rescue v1 (Order.v1 refs it), rescue v2 (gap fill: v1…v3 gap),
 *               nothing deleted → full chain v1-v4 preserved; findChanges works.
 *
 *   Phase 2   More changes
 *               Tag: v2("VIP Plus")
 *               Order: v2(CONFIRMED)  — refs Customer=v4, Tag=v2
 *               Customer: v5(Stuttgart)
 *               Contract: v2 (title + VO.endDate updated) — VO refs Customer=v5
 *
 *   Cleanup 2  keepLatest(1)
 *               Customer: rescue v4 (Order.v2 refs it), delete v1+v3 → promote v4 to INITIAL
 *               Tag: delete v1, promote v2 to INITIAL
 *               Order: delete v1, promote v2 to INITIAL
 *               Contract entity: delete v1, promote v2 to INITIAL
 *               ContractPeriod VO: delete v1, promote v2 to INITIAL
 *               → findChanges on Customer now works: clean chain v4(INITIAL)→v5
 *
 *   Phase 3   One more Customer change
 *               Customer: v6(phone changed)
 *               → findChanges must show only the two diffs since the promoted INITIAL:
 *                  city Frankfurt→Stuttgart (v4→v5) and phone 030→999 (v5→v6)
 * </pre>
 */
@Transactional
abstract class AbstractJaversLifecycleIntegrationTest {

    @Autowired CustomerRepository customerRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TagRepository tagRepo;
    @Autowired ContractRepository contractRepo;
    @Autowired JaversCleanupService cleanupService;
    @Autowired Javers javers;

    @Test
    void lifecycle_multiple_linked_entities_two_cleanups_with_change_history_assertions() {

        // =====================================================================
        // Phase 1: Build initial audit history
        // =====================================================================

        Customer customer = customerRepo.save(
                new Customer("Alice", "alice@test.de", "030", "Berlin")); // v1
        Tag tag = tagRepo.save(new Tag("VIP", "Very Important"));         // v1

        // Order committed when Customer is still at v1 and Tag at v1.
        // State JSON: customer ref → Customer@v1, tag ref → Tag@v1.
        Order order = orderRepo.save(
                new Order("ORD-001", customer, "PENDING", tag));           // v1

        customer.setCity("Munich");    customer = customerRepo.save(customer); // v2
        customer.setCity("Hamburg");   customer = customerRepo.save(customer); // v3
        customer.setCity("Frankfurt"); customer = customerRepo.save(customer); // v4

        // ContractPeriod VO refs Customer (currently at v4 = Frankfurt).
        Contract contract = contractRepo.save(new Contract("SLA-2024",
                new ContractPeriod(
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 12, 31),
                        customer)));                                        // Contract.v1, VO.v1

        assertThat(snapshotCount(customer)).isEqualTo(4); // v1-v4
        assertThat(snapshotCount(tag)).isEqualTo(1);
        assertThat(snapshotCount(order)).isEqualTo(1);
        assertThat(snapshotCount(contract)).isEqualTo(1);

        // =====================================================================
        // Cleanup 1: keepLatest(2)
        //
        // Customer (4 snapshots) → proposed delete [v1, v2], keep [v3, v4].
        //
        // Phase 2 scan:
        //   Order.v1 (retained entity snapshot) refs Customer; its commitDate
        //   falls between Customer.v1 and Customer.v2 → earliestRefDate = T(order.v1).
        //   ContractPeriod.v1 (retained VO snapshot) refs Customer at T(contract.v1) > v4
        //   → not the earliest.
        //
        // Phase 3:
        //   Oldest retained Customer = v3 (T3 > T(order.v1)) → not already covered.
        //   Rescue: most recent deletion candidate with commitDate ≤ T(order.v1) = v1.
        //     (v2 was committed after the Order, so v2.commitDate > T(order.v1))
        //   → rescue v1, toDelete[Customer] = [v2].
        //
        // Phase 4 (gap fill):
        //   Remaining would be [v1, v3, v4] — gap at v2 (v1.version=1, v3.version=3).
        //   v2 is still in toDelete → rescue v2 to close the gap.
        //   → toDelete[Customer] = [] (nothing deleted).
        //
        // Execution: Customer fully preserved; v1 is INITIAL → no promotion.
        // All other entities: ≤2 snapshots → nothing deleted.
        // =====================================================================

        CleanupResult result1 = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result1.rescuedSnapshots()).isEqualTo(2);  // Customer.v1 (ref) + v2 (gap fill)
        assertThat(result1.deletedSnapshots()).isEqualTo(0);  // nothing deleted (gap fill prevents it)
        assertThat(result1.deletedCommits()).isEqualTo(0);
        assertThat(result1.promotedSnapshots()).isEqualTo(0); // v1 is already INITIAL
        assertThat(snapshotCount(customer)).isEqualTo(4);     // v1, v2, v3, v4 — all intact

        // Full chain preserved: findChanges works without gaps.
        // Returns newest-first: Hamburg→Frankfurt, Munich→Hamburg, Berlin→Munich.
        List<ValueChange> cityDiffs1 = cityValueChanges(customer);
        assertThat(cityDiffs1).hasSize(3);
        assertThat(cityDiffs1.get(0).getLeft()).isEqualTo("Hamburg");    // v3→v4
        assertThat(cityDiffs1.get(0).getRight()).isEqualTo("Frankfurt");
        assertThat(cityDiffs1.get(1).getLeft()).isEqualTo("Munich");     // v2→v3
        assertThat(cityDiffs1.get(1).getRight()).isEqualTo("Hamburg");
        assertThat(cityDiffs1.get(2).getLeft()).isEqualTo("Berlin");     // v1→v2
        assertThat(cityDiffs1.get(2).getRight()).isEqualTo("Munich");

        // Tag, Order, Contract: untouched — each still has a single INITIAL snapshot.
        assertThat(findOldestSnapshot(tag).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findOldestSnapshot(order).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(findOldestSnapshot(contract).getType()).isEqualTo(SnapshotType.INITIAL);

        // =====================================================================
        // Phase 2: Further modifications after Cleanup 1
        //
        // Tag:      v2("VIP Plus")
        // Order:    v2(CONFIRMED)  — committed while Customer=v4, Tag=v2
        //                            → state refs Customer@v4, Tag@v2
        // Customer: v5(Stuttgart)
        // Contract: v2 (title + period.endDate updated) + VO.v2  — VO refs Customer=v5
        // =====================================================================

        tag.setLabel("VIP Plus");
        tag = tagRepo.save(tag);                    // Tag.v2

        order.setStatus("CONFIRMED");
        order = orderRepo.save(order);              // Order.v2 (before Customer.v5)

        customer.setCity("Stuttgart");
        customer = customerRepo.save(customer);     // Customer.v5 (after Order.v2)

        contract.setTitle("SLA-2025");
        contract.getPeriod().setEndDate(LocalDate.of(2025, 12, 31));
        contract.getPeriod().setResponsibleCustomer(customer); // VO now refs Customer@v5
        contract = contractRepo.save(contract);     // Contract.v2, VO.v2

        assertThat(snapshotCount(customer)).isEqualTo(5); // v1, v2, v3, v4, v5
        assertThat(snapshotCount(tag)).isEqualTo(2);      // v1, v2
        assertThat(snapshotCount(order)).isEqualTo(2);    // v1, v2
        assertThat(snapshotCount(contract)).isEqualTo(2); // v1, v2

        // =====================================================================
        // Cleanup 2: keepLatest(1)
        //
        // Customer (4 snapshots): proposed delete [v1, v3, v4], keep [v5].
        //   Phase 2 scan (retained snapshots):
        //     Order.v2 refs Customer → refDate = T(order.v2)
        //     ContractPeriod.v2 (VO) refs Customer → refDate = T(contract.v2) > T(customer.v5)
        //     → earliestRefDate[Customer] = T(order.v2)
        //   T(order.v2) < T(customer.v5) (Order was saved before Customer in Phase 2).
        //   Phase 3:
        //     oldest retained = v5, commitDate > T(order.v2) → not already covered.
        //     Best rescue ≤ T(order.v2): v4 (committed in Phase 1, before any Phase 2 save).
        //     → rescue v4, toDelete[Customer] = [v1, v3].
        //   Execution: delete v1+v3; remaining = [v4, v5]; v4 is UPDATE → promote to INITIAL.
        //
        // Tag (2 snapshots): keep [v2], delete [v1].
        //   Order.v2 (retained) refs Tag at T(order.v2); Tag.v2 committed before Order.v2
        //   → alreadyCovered = true → no rescue.
        //   → delete v1, promote v2 to INITIAL.
        //
        // Order: keep [v2], delete [v1] → promote v2 to INITIAL.
        // Contract entity: keep [v2], delete [v1] → promote v2 to INITIAL.
        // ContractPeriod VO: keep [v2], delete [v1] → promote v2 to INITIAL.
        // =====================================================================

        CleanupResult result2 = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        // Customer has v1..v5. keepLatest(1) proposes deleting [v1,v2,v3,v4].
        // Phase 3 rescues v4 (Order.v2 ref). toDelete = [v1,v2,v3].
        // Phase 4: remaining = [v4, v5] — versions 4 and 5 are consecutive → no gap fill.
        // v1+v2+v3 are deleted; v4 is promoted to INITIAL.
        assertThat(result2.rescuedSnapshots()).isEqualTo(1);  // Customer.v4 (ref protection only)
        assertThat(result2.deletedSnapshots()).isEqualTo(7);  // Customer v1+v2+v3, Tag v1,
                                                               // Order v1, Contract v1, VO v1
        assertThat(result2.promotedSnapshots()).isEqualTo(5); // Customer.v4, Tag.v2, Order.v2,
                                                               // Contract.v2, ContractPeriod.v2
        assertThat(snapshotCount(customer)).isEqualTo(2); // v4 (INITIAL) + v5
        assertThat(snapshotCount(tag)).isEqualTo(1);      // v2 (INITIAL)
        assertThat(snapshotCount(order)).isEqualTo(1);    // v2 (INITIAL)
        assertThat(snapshotCount(contract)).isEqualTo(1); // v2 (INITIAL)

        // Customer.v4 (Frankfurt) rescued and promoted — must carry all properties as INITIAL.
        CdoSnapshot customerAnchor = findOldestSnapshot(customer);
        assertThat(customerAnchor.getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(customerAnchor.getState().getPropertyValue("city")).isEqualTo("Frankfurt");
        assertThat(customerAnchor.getChanged())
                .containsExactlyInAnyOrder("id", "name", "email", "phone", "city");

        // Tag.v2 promoted to INITIAL — history now starts from "VIP Plus".
        CdoSnapshot tagAnchor = findOldestSnapshot(tag);
        assertThat(tagAnchor.getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(tagAnchor.getState().getPropertyValue("label")).isEqualTo("VIP Plus");

        // Order.v2 promoted to INITIAL — history now starts from CONFIRMED.
        CdoSnapshot orderAnchor = findOldestSnapshot(order);
        assertThat(orderAnchor.getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(orderAnchor.getState().getPropertyValue("status")).isEqualTo("CONFIRMED");

        // ContractPeriod VO.v2 promoted to INITIAL — extended endDate must be present.
        List<CdoSnapshot> voSnapshots = javers.findSnapshots(
                QueryBuilder.byValueObjectId(contract.getId(), Contract.class, "period").build());
        assertThat(voSnapshots).hasSize(1);
        assertThat(voSnapshots.get(0).getType()).isEqualTo(SnapshotType.INITIAL);
        assertThat(voSnapshots.get(0).getState().getPropertyValue("endDate"))
                .isEqualTo(LocalDate.of(2025, 12, 31));

        // Customer chain after Cleanup 2: [v4(INITIAL, Frankfurt), v5(UPDATE, Stuttgart)].
        // Chain is now gap-free — findChanges works correctly.
        // Exactly one city diff: Frankfurt → Stuttgart.
        List<ValueChange> cityDiffs2 = cityValueChanges(customer);
        assertThat(cityDiffs2).hasSize(1);
        assertThat(cityDiffs2.get(0).getLeft()).isEqualTo("Frankfurt");
        assertThat(cityDiffs2.get(0).getRight()).isEqualTo("Stuttgart");

        // =====================================================================
        // Phase 3: One more save after cleanup — verify diff is clean
        //
        // Customer.v6 changes only phone. Chain: v4(INITIAL)→v5(UPDATE)→v6(UPDATE).
        // findChanges must return exactly two real ValueChanges (ch.getClass() == ValueChange.class
        // excludes InitialValueChange, which is a subtype):
        //   1. phone 030 → 999-999  (v5→v6)
        //   2. city Frankfurt → Stuttgart  (v4→v5)
        // No spurious changes from the deleted snapshots must appear.
        // =====================================================================

        customer.setPhone("999-999");
        customer = customerRepo.save(customer); // v6

        List<Change> allChanges = javers.findChanges(
                QueryBuilder.byInstance(customer).build());

        List<ValueChange> realDiffs = allChanges.stream()
                .filter(c -> c.getClass() == ValueChange.class)
                .map(c -> (ValueChange) c)
                .toList(); // newest-first

        assertThat(realDiffs).hasSize(2);
        // v5 → v6: only phone changed
        assertThat(realDiffs.get(0).getPropertyName()).isEqualTo("phone");
        assertThat(realDiffs.get(0).getLeft()).isEqualTo("030");
        assertThat(realDiffs.get(0).getRight()).isEqualTo("999-999");
        // v4(INITIAL) → v5: only city changed
        assertThat(realDiffs.get(1).getPropertyName()).isEqualTo("city");
        assertThat(realDiffs.get(1).getLeft()).isEqualTo("Frankfurt");
        assertThat(realDiffs.get(1).getRight()).isEqualTo("Stuttgart");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int snapshotCount(Object entity) {
        return javers.findSnapshots(QueryBuilder.byInstance(entity).build()).size();
    }

    private CdoSnapshot findOldestSnapshot(Object entity) {
        return javers.findSnapshots(QueryBuilder.byInstance(entity).build()).stream()
                .min(Comparator.comparingLong(CdoSnapshot::getVersion))
                .orElseThrow();
    }

    /** Returns real city ValueChanges (newest-first), excluding InitialValueChange. */
    private List<ValueChange> cityValueChanges(Customer customer) {
        return javers.findChanges(QueryBuilder.byInstance(customer).build()).stream()
                .filter(c -> c.getClass() == ValueChange.class
                        && "city".equals(((ValueChange) c).getPropertyName()))
                .map(c -> (ValueChange) c)
                .toList();
    }
}
