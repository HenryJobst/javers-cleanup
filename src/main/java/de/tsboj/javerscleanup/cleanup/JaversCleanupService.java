package de.tsboj.javerscleanup.cleanup;

import org.javers.core.Javers;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.repository.jql.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Removes outdated Javers snapshots while preserving audit-history consistency.
 *
 * <p>The core challenge: UPDATE snapshots store only the changed property names in
 * {@code changed_properties}, whereas an INITIAL snapshot must list all properties.
 * When the original INITIAL snapshot is deleted, the oldest remaining snapshot must
 * be promoted correctly:
 * <ul>
 *   <li>{@code type} → {@code INITIAL}</li>
 *   <li>{@code changed_properties} → all property names from {@code state}</li>
 * </ul>
 * The {@code state} column always holds the complete object state (no delta) and
 * does not need to be modified. Property names are read via the Javers
 * {@code CdoSnapshot} API to avoid a direct Jackson dependency.
 */
@Service
public class JaversCleanupService {

    private static final Logger log = LoggerFactory.getLogger(JaversCleanupService.class);

    private final Javers javers;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JaversCleanupService(Javers javers,
                                JdbcTemplate jdbc,
                                NamedParameterJdbcTemplate namedJdbc) {
        this.javers = javers;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    /**
     * Runs a cleanup pass according to the given policy.
     *
     * @return summary of all changes made
     */
    @Transactional
    public CleanupResult cleanup(CleanupPolicy policy) {
        List<Long> globalIds = jdbc.queryForList(
                "SELECT DISTINCT global_id_fk FROM jv_snapshot", Long.class);

        int promoted = 0;
        int deleted = 0;

        for (Long globalId : globalIds) {
            List<SnapshotRow> snapshots = loadSnapshots(globalId);
            List<Long> toDelete = policy.selectForDeletion(snapshots);
            if (toDelete.isEmpty()) continue;

            Set<Long> toDeleteSet = new HashSet<>(toDelete);
            List<SnapshotRow> remaining = snapshots.stream()
                    .filter(s -> !toDeleteSet.contains(s.id()))
                    .toList(); // already sorted ascending by version

            if (!remaining.isEmpty()) {
                SnapshotRow oldestRemaining = remaining.getFirst();
                // Only UPDATE snapshots need promotion; INITIAL is already correct,
                // TERMINAL indicates a deleted entity and should not become INITIAL.
                if ("UPDATE".equals(oldestRemaining.type())) {
                    promoteToInitial(oldestRemaining);
                    promoted++;
                }
            }

            namedJdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk IN (:ids)",
                    Map.of("ids", toDelete));
            deleted += toDelete.size();

            log.debug("GlobalId {}: {} snapshot(s) deleted", globalId, toDelete.size());
        }

        int deletedCommits = cleanupOrphanedData();

        log.info("Cleanup complete: {} promoted, {} snapshots deleted, {} commits deleted",
                promoted, deleted, deletedCommits);

        return new CleanupResult(promoted, deleted, deletedCommits, 0);
    }

    // -------------------------------------------------------------------------

    private List<SnapshotRow> loadSnapshots(long globalId) {
        return jdbc.query("""
                SELECT s.snapshot_pk, s.version, s.type, s.changed_properties, s.commit_fk,
                       c.commit_date, g.type_name, g.local_id
                FROM jv_snapshot s
                JOIN jv_commit c ON s.commit_fk = c.commit_pk
                JOIN jv_global_id g ON s.global_id_fk = g.global_id_pk
                WHERE s.global_id_fk = ?
                ORDER BY s.version ASC
                """,
                (rs, i) -> new SnapshotRow(
                        rs.getLong("snapshot_pk"),
                        rs.getLong("version"),
                        rs.getString("type"),
                        rs.getString("changed_properties"),
                        rs.getLong("commit_fk"),
                        rs.getTimestamp("commit_date").toLocalDateTime(),
                        rs.getString("type_name"),
                        rs.getString("local_id")
                ),
                globalId);
    }

    /**
     * Promotes an UPDATE snapshot to INITIAL.
     *
     * <p>The {@code state} column already contains the full object state and is left
     * unchanged. Only {@code type} and {@code changed_properties} are updated so that
     * Javers interprets this snapshot as a complete initial creation.
     */
    private void promoteToInitial(SnapshotRow snapshot) {
        Set<String> allPropertyNames = loadPropertyNamesFromJavers(snapshot);
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

    /**
     * Reads all property names for the given snapshot via the Javers API.
     * Uses {@code CdoSnapshotState.getPropertyNames()} to stay independent of
     * the concrete JSON library (Jackson 2/3).
     */
    @SuppressWarnings("unchecked")
    private Set<String> loadPropertyNamesFromJavers(SnapshotRow row) {
        try {
            Class<?> entityClass = Class.forName(row.typeName());
            List<CdoSnapshot> snapshots = javers.findSnapshots(
                    QueryBuilder.byInstanceId(parseLocalId(row.localId()), entityClass).build());
            return snapshots.stream()
                    .filter(s -> s.getVersion() == row.version())
                    .findFirst()
                    .map(s -> s.getState().getPropertyNames())
                    .orElseThrow(() -> new IllegalStateException(
                            "Snapshot v%d for %s/%s not found in Javers"
                                    .formatted(row.version(), row.typeName(), row.localId())));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Entity class not found: " + row.typeName(), e);
        }
    }

    /**
     * Removes orphaned data in FK-safe order:
     * jv_commit_property → jv_commit → jv_global_id (children first, then parents).
     */
    private int cleanupOrphanedData() {
        jdbc.update("""
                DELETE FROM jv_commit_property
                WHERE commit_fk NOT IN (SELECT DISTINCT commit_fk FROM jv_snapshot)
                """);

        int deletedCommits = jdbc.update("""
                DELETE FROM jv_commit
                WHERE commit_pk NOT IN (SELECT DISTINCT commit_fk FROM jv_snapshot)
                """);

        // Delete orphaned child global IDs (value objects) first
        jdbc.update("""
                DELETE FROM jv_global_id
                WHERE owner_id_fk IS NOT NULL
                  AND global_id_pk NOT IN (SELECT DISTINCT global_id_fk FROM jv_snapshot)
                """);

        jdbc.update("""
                DELETE FROM jv_global_id
                WHERE global_id_pk NOT IN (SELECT DISTINCT global_id_fk FROM jv_snapshot)
                """);

        return deletedCommits;
    }

    private static Object parseLocalId(String localId) {
        try { return Long.parseLong(localId); }
        catch (NumberFormatException e) { return localId; }
    }
}
