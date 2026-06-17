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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JaversCleanupServiceTest {

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
        // v1=INITIAL, v2=UPDATE, v3=UPDATE, v4=UPDATE → keepLatest(2) löscht v1+v2, befördert v3

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot oldestRemaining = findOldestSnapshot(c);
        assertThat(oldestRemaining.getType()).isEqualTo(SnapshotType.INITIAL);
    }

    @Test
    void cleanup_befoerdertNicht_wennAeltesterVerbleibenderBereitsInitial() {
        Customer c = createCustomerWithSnapshots(3);
        // v1=INITIAL, v2=UPDATE, v3=UPDATE → keepLatest(2) löscht v1, befördert v2

        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        assertThat(result.promotedSnapshots()).isEqualTo(1);
        assertThat(findOldestSnapshot(c).getType()).isEqualTo(SnapshotType.INITIAL);
    }

    // -------------------------------------------------------------------------
    // Verhalten 3 (Tracer Bullet): Beförderter Snapshot enthält ALLE Properties
    // -------------------------------------------------------------------------

    @Test
    void befoerderterInitialSnapshot_enthaeltAlleProperties() {
        // Anlegen: alle 4 Felder belegt → INITIAL hat changed=[name,email,phone,city]
        Customer c = repo.save(new Customer("Anna Schmidt", "anna@test.de", "030-1", "Hamburg"));
        // v2: nur email geändert → UPDATE hat changed=[email]
        c.setEmail("anna.neu@test.de"); c = repo.save(c);
        // v3: nur phone → UPDATE hat changed=[phone]
        c.setPhone("030-2");            c = repo.save(c);

        // keepLatest(2) → löscht v1 (INITIAL), befördert v2 (UPDATE, changed=[email]) zu INITIAL
        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        CdoSnapshot promotedInitial = findOldestSnapshot(c);
        assertThat(promotedInitial.getType()).isEqualTo(SnapshotType.INITIAL);
        // Entscheidend: changed_properties muss ALLE Felder enthalten, nicht nur ["email"]
        // Javers 7.x tracked auch @Id-Felder als Properties
        assertThat(promotedInitial.getChanged())
                .containsExactlyInAnyOrder("id", "name", "email", "phone", "city");
    }

    @Test
    void befoerderterInitialSnapshot_state_bleibtUnveraendert() {
        Customer c = repo.save(new Customer("Bob Bauer", "bob@test.de", "040-1", "Bremen"));
        c.setCity("Kiel"); c = repo.save(c);
        c.setCity("Flensburg"); c = repo.save(c);

        cleanupService.cleanup(CleanupPolicy.keepLatest(2));

        // State (der tatsächliche Objektinhalt) muss nach Beförderung noch stimmen
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

        // 3 Snapshots gelöscht → ihre Commits sind verwaist
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

        assertThat(result.deletedSnapshots()).isEqualTo(1 + 3); // 3-2=1, 5-2=3
        assertThat(snapshotCount(c1)).isEqualTo(2);
        assertThat(snapshotCount(c2)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    /** Legt einen Customer an und speichert ihn {@code snapshotCount}-mal (inkl. Anlage). */
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
