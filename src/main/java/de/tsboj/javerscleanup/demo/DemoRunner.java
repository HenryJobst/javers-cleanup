package de.tsboj.javerscleanup.demo;

import de.tsboj.javerscleanup.cleanup.CleanupPolicy;
import de.tsboj.javerscleanup.cleanup.CleanupResult;
import de.tsboj.javerscleanup.cleanup.JaversCleanupService;
import org.javers.core.Javers;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.repository.jql.QueryBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public class DemoRunner implements CommandLineRunner {

    private final CustomerRepository repo;
    private final JaversCleanupService cleanupService;
    private final Javers javers;

    public DemoRunner(CustomerRepository repo,
                      JaversCleanupService cleanupService,
                      Javers javers) {
        this.repo = repo;
        this.cleanupService = cleanupService;
        this.javers = javers;
    }

    @Override
    public void run(String... args) {
        // Kunde anlegen und mehrfach ändern → 5 Snapshots
        Customer c = repo.save(new Customer("Max Mustermann", "max@example.de", "030-100", "Berlin"));
        c.setEmail("max.neu@example.de");   c = repo.save(c);   // v2: email geändert
        c.setPhone("030-200");              c = repo.save(c);   // v3: phone geändert
        c.setCity("München");               c = repo.save(c);   // v4: city geändert
        c.setName("Maximilian Mustermann"); c = repo.save(c);   // v5: name geändert

        printSnapshots("VOR Cleanup", c);

        // Policy: nur die letzten 2 Snapshots behalten
        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));
        System.out.println("\n=== Cleanup-Ergebnis ===");
        System.out.println("  " + result);

        printSnapshots("NACH Cleanup (keepLatest=2)", c);

        // Zweiter Kunde: Zeit-basierte Policy demonstrieren
        // (im Demo sofort gelöscht, da alle Snapshots "jetzt" sind → minKeep=1 greift)
        Customer c2 = repo.save(new Customer("Erika Muster", "erika@example.de", "089-100", "Hamburg"));
        c2.setCity("Frankfurt"); c2 = repo.save(c2);
        c2.setCity("Köln");      c2 = repo.save(c2);

        printSnapshots("Kunde 2 VOR Cleanup", c2);

        CleanupResult result2 = cleanupService.cleanup(CleanupPolicy.olderThan(30, 1));
        System.out.println("\n=== Cleanup-Ergebnis (olderThan 30 Tage, min 1) ===");
        System.out.println("  " + result2 + "  (keine Löschung, da Snapshots < 30 Tage alt)");
    }

    private void printSnapshots(String label, Customer c) {
        List<CdoSnapshot> snapshots = javers.findSnapshots(
                QueryBuilder.byInstance(c).build());
        System.out.printf("%n=== %s ===%n", label);
        snapshots.stream()
                .sorted((a, b) -> Long.compare(a.getVersion(), b.getVersion()))
                .forEach(s -> System.out.printf(
                        "  v%-2d [%-8s] changed=%s%n",
                        s.getVersion(), s.getType(), s.getChanged()));
    }
}
