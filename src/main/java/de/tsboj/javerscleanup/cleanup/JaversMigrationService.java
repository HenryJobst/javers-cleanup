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
 * Erstellt retroaktiv Javers-Snapshots für Entitäten, die während einer Migration
 * ohne Javers-Auditing angelegt oder geändert wurden.
 *
 * <h3>Problemfall: fehlender INITIAL-Snapshot</h3>
 * Wurde der INITIAL-Snapshot einer Entität gelöscht (z.B. durch ein vorheriges Cleanup
 * ohne korrekte Beförderung), besteht die Kette nur noch aus UPDATE-Snapshots.
 * Ein nachträglicher {@code javers.commit()} würde einen weiteren UPDATE anhängen —
 * ohne dass je ein INITIAL existiert. Dieser Service erkennt und korrigiert diesen
 * Zustand automatisch nach dem Commit.
 *
 * <h3>Typen</h3>
 * <ul>
 *   <li>Neue Entität (kein Snapshot) → INITIAL</li>
 *   <li>Bestehende Entität geändert (INITIAL vorhanden) → UPDATE mit Diff</li>
 *   <li>Bestehende Entität, INITIAL fehlt → UPDATE + Korrektur des ältesten zu INITIAL</li>
 *   <li>Unveränderte Entität → kein neuer Snapshot</li>
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
     * Erstellt retroaktiv Snapshots mit dem aktuellen Zeitstempel.
     */
    @Transactional
    public <T> MigrationResult commitAll(Collection<T> entities, String author) {
        return doCommit(entities, author, null);
    }

    /**
     * Erstellt retroaktiv Snapshots mit einem historischen Migrationszeitstempel.
     * Nützlich um die Snapshots zeitlich korrekt in der Audit-Historie einzuordnen.
     * Javers bietet dafür kein Public-API; die Anpassung erfolgt direkt in {@code jv_commit}.
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

        // Korrektur: ältester Snapshot ist UPDATE ohne vorherigen INITIAL → zu INITIAL befördern
        int corrected = promoteOrphanedOldestUpdates(maxSnapshotPkBefore);

        if (migrationTimestamp != null) {
            backdateNewCommits(maxCommitPkBefore, migrationTimestamp);
        }

        return buildResult(maxSnapshotPkBefore, entities.size(), corrected);
    }

    /**
     * Findet Entitäten, bei denen nach dem Commit der ÄLTESTE Snapshot vom Typ UPDATE ist
     * (kein INITIAL in der gesamten Kette). Dies passiert wenn ein früheres INITIAL
     * gelöscht wurde ohne Beförderung eines anderen Snapshots.
     *
     * <p>Die Suche beschränkt sich auf Entitäten, die in diesem Lauf tatsächlich
     * committet wurden (neue snapshot_pk > maxBefore).
     */
    private int promoteOrphanedOldestUpdates(long maxSnapshotPkBefore) {
        // Für alle global_ids, die in diesem Lauf neue Snapshots bekommen haben:
        // Finde den jeweils ÄLTESTEN Snapshot — wenn der UPDATE ist, muss er zu INITIAL befördert werden.
        List<SnapshotRow> toPromote = jdbc.query("""
                SELECT s.snapshot_pk, s.version, s.type, s.changed_properties, s.commit_fk,
                       c.commit_date, g.type_name, g.local_id
                FROM jv_snapshot s
                JOIN jv_commit c ON s.commit_fk = c.commit_pk
                JOIN jv_global_id g ON s.global_id_fk = g.global_id_pk
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
                        rs.getString("local_id")
                ),
                maxSnapshotPkBefore);

        for (SnapshotRow row : toPromote) {
            promoteToInitial(row);
        }

        if (!toPromote.isEmpty()) {
            log.warn("{} Snapshot(s) ohne vorherigen INITIAL gefunden und korrigiert " +
                     "(ältester Snapshot war UPDATE — fehlende INITIAL-Beförderung in früherem Cleanup?)",
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

        log.debug("Snapshot {} (v{}) nachträglich zu INITIAL befördert", snapshot.id(), snapshot.version());
    }

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
                            "Snapshot v%d für %s/%s nicht in Javers gefunden"
                                    .formatted(row.version(), row.typeName(), row.localId())));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Entity-Klasse nicht gefunden: " + row.typeName(), e);
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
