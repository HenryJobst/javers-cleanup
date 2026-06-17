package de.tsboj.javerscleanup;

import de.tsboj.javerscleanup.cleanup.CleanupPolicy;
import de.tsboj.javerscleanup.cleanup.CleanupResult;
import de.tsboj.javerscleanup.cleanup.JaversCleanupService;
import de.tsboj.javerscleanup.demo.Customer;
import de.tsboj.javerscleanup.demo.CustomerRepository;
import org.javers.core.Javers;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.SnapshotType;
import org.javers.repository.jql.QueryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
abstract class AbstractJaversCleanupServiceTest {

    @Autowired CustomerRepository repo;
    @Autowired JaversCleanupService cleanupService;
    @Autowired Javers javers;

    // -------------------------------------------------------------------------
    // Verhalten 1: Korrekte Anzahl Snapshots wird gelöscht
    // -------------------------------------------------------------------------

    @Test
    void keepLatest_loeschtDieAeltestenSnapshots() {
        Customer c = createCustomerWithSnapshots(4);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.deletedSnapshots()).isEqualTo(2);
        assertThat(snapshotCount(c)).isEqualTo(2);
    }

    @Test
    void keepLatest_loeschtNichts_wennWenigerAlsMinVorhanden() {
        Customer c = createCustomerWithSnapshots(2);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(3));

        assertThat(result.deletedSnapshots()).isEqualTo(0);
        assertThat(snapshotCount(c)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Verhalten 2: Ältester verbleibender UPDATE-Snapshot wird zu INITIAL befördert
    // -------------------------------------------------------------------------

    @Test
    void cleanup_befoerdertAeltestenVerbleibendenSnapshotZuInitial() {
        Customer c = createCustomerWithSnapshots(4);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot oldestRemaining = findOldestSnapshot(c);
        assertThat(oldestRemaining.getType()).isEqualTo(SnapshotType.INITIAL);
    }

    @Test
    void cleanup_befoerdertNicht_wennAeltesterVerbleibenderBereitsInitial() {
        Customer c = createCustomerWithSnapshots(3);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.promotedSnapshots()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    // -------------------------------------------------------------------------
    // Verhalten 3: Beförderter Snapshot enthält ALLE Properties
    // -------------------------------------------------------------------------

    @Test
    void befoerderterInitialSnapshot_enthaeltAlleProperties() {
        Customer c = repo.save(new Customer("Anna Schmidt", "anna@test.de", "030-1", "Hamburg"));
        c.setEmail("anna.neu@test.de"); c = repo.save(c);
        c.setPhone("030-2");            c = repo.save(c);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot promotedInitial = findOldestSnapshot(c);
        assertThat(promotedInitial.getType()).isEqualTo(SnapshotType.INITIAL);
        // Javers 7.x tracked auch @Id-Felder als Properties
        assertThat(promotedInitial.getChanged())
                .containsExactlyInAnyOrder("id", "name", "email", "phone", "city");
    }

    @Test
    void befoerderterInitialSnapshot_state_bleibtUnveraendert() {
        Customer c = repo.save(new Customer("Bob Bauer", "bob@test.de", "040-1", "Bremen"));
        c.setCity("Kiel");      c = repo.save(c);
        c.setCity("Flensburg"); c = repo.save(c);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot promoted = findOldestSnapshot(c);
        assertThat(promoted.getState().getPropertyValue("city")).isEqualTo("Kiel");
        assertThat(promoted.getState().getPropertyValue("name")).isEqualTo("Bob Bauer");
    }

    // -------------------------------------------------------------------------
    // Verhalten 4: Verwaiste Commits werden bereinigt
    // -------------------------------------------------------------------------

    @Test
    void cleanup_entferntVerwaisteCommits() {
        Customer c = createCustomerWithSnapshots(4);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(1));

        assertThat(result.deletedCommits()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Verhalten 5: Mehrere Entitäten werden unabhängig bereinigt
    // -------------------------------------------------------------------------

    @Test
    void cleanup_behandeltMehrereEntitaetenUnabhaengig() {
        Customer c1 = createCustomerWithSnapshots(3);
        Customer c2 = createCustomerWithSnapshots(5);

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.deletedSnapshots()).isEqualTo(1 + 3);
        assertThat(snapshotCount(c1)).isEqualTo(2);
        assertThat(snapshotCount(c2)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private Customer createCustomerWithSnapshots(int snapshotCount) {
        Customer c = repo.save(new Customer("Test User", "test@example.de", "000", "Berlin"));
        for (int i = 1; i < snapshotCount; i++) {
            c.setCity("Stadt-" + i);
            c = repo.save(c);
        }
        return c;
    }

    private int snapshotCount(Customer c) {
        return javers.findSnapshots(QueryBuilder.byInstance(c).build()).size();
    }

    private CdoSnapshot findOldestSnapshot(Customer c) {
        List<CdoSnapshot> snapshots = javers.findSnapshots(
                QueryBuilder.byInstance(c).build());
        return snapshots.stream()
                .min(Comparator.comparingLong(CdoSnapshot::getVersion))
                .orElseThrow();
    }
}
