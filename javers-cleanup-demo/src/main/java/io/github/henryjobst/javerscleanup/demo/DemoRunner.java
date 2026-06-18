package io.github.henryjobst.javerscleanup.demo;

import io.github.henryjobst.javerscleanup.CleanupPolicy;
import io.github.henryjobst.javerscleanup.CleanupResult;
import io.github.henryjobst.javerscleanup.JaversCleanupService;
import org.javers.core.Javers;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.repository.jql.QueryBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
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
        // Create a customer and modify it four times → 5 snapshots
        Customer c = repo.save(new Customer("Max Mustermann", "max@example.de", "030-100", "Berlin"));
        c.setEmail("max.new@example.de");     c = repo.save(c); // v2: email changed
        c.setPhone("030-200");                c = repo.save(c); // v3: phone changed
        c.setCity("Munich");                  c = repo.save(c); // v4: city changed
        c.setName("Maximilian Mustermann");   c = repo.save(c); // v5: name changed

        printSnapshots("BEFORE cleanup", c);

        // Policy: keep only the last 2 snapshots
        CleanupResult result = cleanupService.cleanup(CleanupPolicy.keepLatest(2));
        System.out.println("\n=== Cleanup result ===");
        System.out.println("  " + result);

        printSnapshots("AFTER cleanup (keepLatest=2)", c);

        // Second customer: demonstrate time-based policy
        // (nothing deleted in demo since all snapshots were just created — minKeep=1 applies)
        Customer c2 = repo.save(new Customer("Erika Muster", "erika@example.de", "089-100", "Hamburg"));
        c2.setCity("Frankfurt"); c2 = repo.save(c2);
        c2.setCity("Cologne");   c2 = repo.save(c2);

        printSnapshots("Customer 2 BEFORE cleanup", c2);

        CleanupResult result2 = cleanupService.cleanup(CleanupPolicy.olderThan(30, 1));
        System.out.println("\n=== Cleanup result (olderThan 30 days, min 1) ===");
        System.out.println("  " + result2 + "  (nothing deleted — snapshots are < 30 days old)");
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
