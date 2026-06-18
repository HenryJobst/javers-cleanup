package io.github.henryjobst.javerscleanup;

import org.javers.core.Javers;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.repository.jql.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helper for promoting an UPDATE snapshot to INITIAL.
 * Used by both {@link JaversCleanupService} and {@link JaversMigrationService}.
 */
class SnapshotPromoter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPromoter.class);

    private final Javers javers;
    private final JdbcTemplate jdbc;

    SnapshotPromoter(Javers javers, JdbcTemplate jdbc) {
        this.javers = javers;
        this.jdbc = jdbc;
    }

    void promoteToInitial(SnapshotRow snapshot) {
        Set<String> allPropertyNames = loadPropertyNames(snapshot);
        // Java identifier names contain no quotes or backslashes, so this manual
        // JSON array format is correct and safe.
        String allPropertiesJson = allPropertyNames.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        jdbc.update(
                "UPDATE jv_snapshot SET type = 'INITIAL', changed_properties = ? WHERE snapshot_pk = ?",
                allPropertiesJson, snapshot.id());

        log.debug("Snapshot {} (v{}) promoted to INITIAL", snapshot.id(), snapshot.version());
    }

    Set<String> loadPropertyNames(SnapshotRow row) {
        try {
            List<CdoSnapshot> snapshots;
            if (row.isValueObject()) {
                Class<?> ownerClass = Class.forName(row.ownerTypeName());
                snapshots = javers.findSnapshots(
                        QueryBuilder.byValueObjectId(
                                parseLocalId(row.ownerLocalId()), ownerClass, row.fragment()
                        ).build());
            } else {
                Class<?> entityClass = Class.forName(row.typeName());
                snapshots = javers.findSnapshots(
                        QueryBuilder.byInstanceId(parseLocalId(row.localId()), entityClass).build());
            }
            return snapshots.stream()
                    .filter(s -> s.getVersion() == row.version())
                    .findFirst()
                    .map(s -> s.getState().getPropertyNames())
                    .orElseThrow(() -> new IllegalStateException(
                            "Snapshot v%d for %s (fragment=%s) not found in Javers"
                                    .formatted(row.version(), row.typeName(), row.fragment())));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Class not found: " + (row.isValueObject() ? row.ownerTypeName() : row.typeName()), e);
        }
    }

    /**
     * Converts a {@code local_id} string from {@code jv_global_id} to the Java object
     * expected by {@link QueryBuilder#byInstanceId}.
     *
     * <p>Javers serializes numeric IDs as bare decimal strings (e.g. {@code "5"}) and
     * non-numeric IDs as JSON strings with surrounding double quotes (e.g. {@code "\"PRIO-1\""}).
     * Strip those quotes so that {@code byInstanceId} receives the actual value.
     */
    static Object parseLocalId(String localId) {
        try { return Long.parseLong(localId); }
        catch (NumberFormatException e) { return stripJsonQuotes(localId); }
    }

    /**
     * Returns a lookup key for {@code jv_global_id.local_id} that matches the key produced
     * by {@code extractEntityRefs} from snapshot state JSON.
     *
     * <p>Numeric IDs are stored without quotes; non-numeric IDs are stored as JSON strings
     * (with surrounding double quotes). Both must produce the same key as
     * {@code cdoIdNode.asText()}, which strips quotes for JSON string nodes.
     */
    static String normalizeLocalId(String localId) {
        try {
            Long.parseLong(localId);
            return localId; // numeric: stored and extracted identically
        } catch (NumberFormatException e) {
            return stripJsonQuotes(localId);
        }
    }

    private static String stripJsonQuotes(String s) {
        if (s != null && s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
