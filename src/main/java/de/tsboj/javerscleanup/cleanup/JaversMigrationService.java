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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retroactively creates Javers snapshots for entities that were inserted or modified
 * during a migration run with Javers auditing disabled.
 *
 * <h3>The missing INITIAL problem</h3>
 * If the INITIAL snapshot of an entity was deleted (e.g. by a previous cleanup without
 * proper promotion), the chain consists only of UPDATE snapshots. A subsequent
 * {@code javers.commit()} would append another UPDATE, leaving the chain without any
 * INITIAL snapshot. This service detects and corrects that state automatically after
 * each commit batch.
 *
 * <h3>Snapshot types created</h3>
 * <ul>
 *   <li>New entity (no snapshot) → INITIAL</li>
 *   <li>Existing entity changed (INITIAL present) → UPDATE with diff</li>
 *   <li>Existing entity, INITIAL missing → UPDATE + oldest UPDATE corrected to INITIAL</li>
 *   <li>Unchanged entity → no new snapshot</li>
 * </ul>
 */
@Service
public class JaversMigrationService {

    private static final Logger log = LoggerFactory.getLogger(JaversMigrationService.class);

    private final Javers javers;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JaversMigrationService(Javers javers, JdbcTemplate jdbc,
                                  NamedParameterJdbcTemplate namedJdbc) {
        this.javers = javers;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    /**
     * Retroactively creates snapshots using the current timestamp.
     */
    @Transactional
    public <T> MigrationResult commitAll(Collection<T> entities, String author) {
        return doCommit(entities, author, null);
    }

    /**
     * Retroactively creates snapshots with a historical migration timestamp.
     * Useful for placing snapshots at the correct point in the audit timeline.
     * Javers provides no public API for this; the timestamp is applied directly
     * to {@code jv_commit} after the commit.
     */
    @Transactional
    public <T> MigrationResult commitAllAt(Collection<T> entities, String author,
                                           Instant migrationTimestamp) {
        return doCommit(entities, author, migrationTimestamp);
    }

    // -------------------------------------------------------------------------

    private <T> MigrationResult doCommit(Collection<T> entities, String author,
                                         Instant migrationTimestamp) {
        long maxSnapshotPkBefore = currentMaxSnapshotPk();
        long maxCommitPkBefore = currentMaxCommitPk();

        for (T entity : entities) {
            javers.commit(author, entity);
        }

        // Fix: oldest snapshot is UPDATE without a prior INITIAL → promote to INITIAL
        int corrected = promoteOrphanedOldestUpdates(maxSnapshotPkBefore);

        if (migrationTimestamp != null) {
            backdateNewCommits(maxCommitPkBefore, migrationTimestamp);
        }

        return buildResult(maxSnapshotPkBefore, entities.size(), corrected);
    }

    /**
     * Finds entities where, after the commit, the absolute oldest snapshot has type UPDATE
     * (no INITIAL anywhere in the chain). This happens when a prior INITIAL was deleted
     * without promoting another snapshot to take its place.
     *
     * <p>The search is scoped to entities that actually received a new snapshot in this run
     * (snapshot_pk > maxBefore).
     */
    private int promoteOrphanedOldestUpdates(long maxSnapshotPkBefore) {
        List<SnapshotRow> toPromote = jdbc.query("""
                SELECT s.snapshot_pk, s.version, s.type, s.changed_properties, s.commit_fk,
                       c.commit_date, g.type_name, g.local_id, g.fragment,
                       og.type_name AS owner_type_name, og.local_id AS owner_local_id
                FROM jv_snapshot s
                JOIN jv_commit c ON s.commit_fk = c.commit_pk
                JOIN jv_global_id g ON s.global_id_fk = g.global_id_pk
                LEFT JOIN jv_global_id og ON g.owner_id_fk = og.global_id_pk
                WHERE s.global_id_fk IN (
                    SELECT DISTINCT global_id_fk FROM jv_snapshot WHERE snapshot_pk > ?
                )
                  AND s.type = 'UPDATE'
                  AND NOT EXISTS (
                      SELECT 1 FROM jv_snapshot s2
                      WHERE s2.global_id_fk = s.global_id_fk
                        AND s2.snapshot_pk < s.snapshot_pk
                  )
                """,
                (rs, i) -> new SnapshotRow(
                        rs.getLong("snapshot_pk"),
                        rs.getLong("version"),
                        rs.getString("type"),
                        rs.getString("changed_properties"),
                        rs.getLong("commit_fk"),
                        rs.getTimestamp("commit_date").toLocalDateTime(),
                        rs.getString("type_name"),
                        rs.getString("local_id"),
                        rs.getString("fragment"),
                        rs.getString("owner_type_name"),
                        rs.getString("owner_local_id")
                ),
                maxSnapshotPkBefore);

        for (SnapshotRow row : toPromote) {
            promoteToInitial(row);
        }

        if (!toPromote.isEmpty()) {
            log.warn("{} snapshot(s) found without a prior INITIAL and corrected " +
                     "(oldest snapshot was UPDATE — missing INITIAL promotion in a previous cleanup?)",
                    toPromote.size());
        }

        return toPromote.size();
    }

    private void promoteToInitial(SnapshotRow snapshot) {
        Set<String> propertyNames = loadPropertyNamesFromJavers(snapshot);
        String allPropertiesJson = propertyNames.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        jdbc.update(
                "UPDATE jv_snapshot SET type = 'INITIAL', changed_properties = ? WHERE snapshot_pk = ?",
                allPropertiesJson, snapshot.id());

        log.debug("Snapshot {} (v{}) retroactively promoted to INITIAL", snapshot.id(), snapshot.version());
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadPropertyNamesFromJavers(SnapshotRow row) {
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

    private void backdateNewCommits(long maxCommitPkBefore, Instant timestamp) {
        jdbc.update("""
                UPDATE jv_commit
                SET commit_date = ?, commit_date_instant = ?
                WHERE commit_pk > ?
                """,
                Timestamp.from(timestamp), timestamp.toString(), maxCommitPkBefore);
    }

    private MigrationResult buildResult(long maxSnapshotPkBefore, int totalEntities, int corrected) {
        List<java.util.Map<String, Object>> stats = jdbc.queryForList("""
                SELECT type, COUNT(*) as cnt
                FROM jv_snapshot
                WHERE snapshot_pk > ?
                GROUP BY type
                """, maxSnapshotPkBefore);

        int initial = 0, update = 0;
        for (var row : stats) {
            int cnt = ((Number) row.get("cnt")).intValue();
            if ("INITIAL".equals(row.get("type"))) initial = cnt;
            else if ("UPDATE".equals(row.get("type"))) update = cnt;
        }

        int unchanged = Math.max(0, totalEntities - initial - update);
        return new MigrationResult(initial, update, unchanged, corrected);
    }

    private long currentMaxSnapshotPk() {
        Long max = jdbc.queryForObject("SELECT MAX(snapshot_pk) FROM jv_snapshot", Long.class);
        return max != null ? max : 0L;
    }

    private long currentMaxCommitPk() {
        Long max = jdbc.queryForObject("SELECT MAX(commit_pk) FROM jv_commit", Long.class);
        return max != null ? max : 0L;
    }

    private static Object parseLocalId(String localId) {
        try { return Long.parseLong(localId); }
        catch (NumberFormatException e) { return localId; }
    }
}
