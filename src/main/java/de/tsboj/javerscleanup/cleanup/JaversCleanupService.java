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
 * Bereinigt veraltete Javers-Snapshots und stellt dabei die Konsistenz der Audit-Historie sicher.
 *
 * <p>Das zentrale Problem bei Javers-Cleanup: Snapshots vom Typ UPDATE speichern in
 * {@code changed_properties} nur die geänderten Felder. Ein INITIAL-Snapshot hingegen
 * enthält alle Felder. Wenn der ursprüngliche INITIAL-Snapshot gelöscht wird, muss der
 * älteste verbleibende Snapshot korrekt zu INITIAL befördert werden:
 * <ul>
 *   <li>{@code type} → {@code INITIAL}</li>
 *   <li>{@code changed_properties} → alle Property-Namen aus dem {@code state}-JSON</li>
 * </ul>
 * Der {@code state} selbst ist immer vollständig (kein Delta) und muss nicht angepasst werden.
 * Die Property-Namen werden direkt über die Javers-API aus dem {@code CdoSnapshot.getState()}
 * gelesen, ohne Abhängigkeit auf eine separate JSON-Bibliothek.
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
     * Führt einen Cleanup-Lauf gemäß der übergebenen Policy durch.
     *
     * @return Zusammenfassung der vorgenommenen Änderungen
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
                    .toList(); // bereits aufsteigend nach Version sortiert

            if (!remaining.isEmpty()) {
                SnapshotRow oldestRemaining = remaining.getFirst();
                // Nur UPDATE-Snapshots müssen befördert werden; INITIAL ist bereits korrekt,
                // TERMINAL würde auf eine gelöschte Entität hinweisen (kein sinnvoller INITIAL).
                if ("UPDATE".equals(oldestRemaining.type())) {
                    promoteToInitial(oldestRemaining);
                    promoted++;
                }
            }

            namedJdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk IN (:ids)",
                    Map.of("ids", toDelete));
            deleted += toDelete.size();

            log.debug("GlobalId {}: {} Snapshots gelöscht", globalId, toDelete.size());
        }

        int deletedCommits = cleanupOrphanedData();

        log.info("Cleanup abgeschlossen: {} befördert, {} Snapshots gelöscht, {} Commits gelöscht",
                promoted, deleted, deletedCommits);

        return new CleanupResult(promoted, deleted, deletedCommits, 0);
    }

    // -------------------------------------------------------------------------
    // Interne Hilfsmethoden
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
     * Befördert einen UPDATE-Snapshot zum INITIAL-Snapshot.
     *
     * <p>Property-Namen werden über die Javers-API aus dem {@code CdoSnapshotState}
     * gelesen — der {@code state}-JSON enthält bereits den vollständigen Objektzustand
     * und muss nicht verändert werden. Lediglich {@code type} und {@code changed_properties}
     * werden korrigiert.
     */
    private void promoteToInitial(SnapshotRow snapshot) {
        Set<String> allPropertyNames = loadPropertyNamesFromJavers(snapshot);
        // Java-Identifier-Namen enthalten keine Anführungszeichen oder Backslashes,
        // daher ist dieses manuelle JSON-Array-Format korrekt und sicher.
        String allPropertiesJson = allPropertyNames.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        jdbc.update(
                "UPDATE jv_snapshot SET type = 'INITIAL', changed_properties = ? WHERE snapshot_pk = ?",
                allPropertiesJson, snapshot.id());

        log.debug("Snapshot {} (v{}) zu INITIAL befördert, changed_properties={}",
                snapshot.id(), snapshot.version(), allPropertiesJson);
    }

    /**
     * Lädt alle Property-Namen des Snapshots über die Javers-API.
     * Verwendet {@code CdoSnapshotState.getPropertyNames()}, um unabhängig von
     * der konkreten JSON-Bibliothek (Jackson 2/3) zu bleiben.
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
                            "Snapshot v%d für %s/%s in Javers nicht gefunden"
                                    .formatted(row.version(), row.typeName(), row.localId())));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Entity-Klasse nicht gefunden: " + row.typeName(), e);
        }
    }

    /** Javers speichert die local_id als String; meistens ist es eine Long-Zahl. */
    private static Object parseLocalId(String localId) {
        try {
            return Long.parseLong(localId);
        } catch (NumberFormatException e) {
            return localId;
        }
    }

    /**
     * Bereinigt verwaiste Datensätze in der richtigen Reihenfolge (FK-Constraints beachten):
     * zuerst jv_commit_property, dann jv_commit, dann jv_global_id.
     */
    private int cleanupOrphanedData() {
        // Commit-Properties für verwaiste Commits löschen
        jdbc.update("""
                DELETE FROM jv_commit_property
                WHERE commit_fk NOT IN (SELECT DISTINCT commit_fk FROM jv_snapshot)
                """);

        // Verwaiste Commits löschen
        int deletedCommits = jdbc.update("""
                DELETE FROM jv_commit
                WHERE commit_pk NOT IN (SELECT DISTINCT commit_fk FROM jv_snapshot)
                """);

        // Verwaiste Kind-GlobalIds löschen (ValueObjects)
        jdbc.update("""
                DELETE FROM jv_global_id
                WHERE owner_id_fk IS NOT NULL
                  AND global_id_pk NOT IN (SELECT DISTINCT global_id_fk FROM jv_snapshot)
                """);

        // Verwaiste Eltern-GlobalIds löschen
        jdbc.update("""
                DELETE FROM jv_global_id
                WHERE global_id_pk NOT IN (SELECT DISTINCT global_id_fk FROM jv_snapshot)
                """);

        return deletedCommits;
    }
}
