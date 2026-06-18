package de.tsboj.javerscleanup.cleanup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javers.core.Javers;
import org.javers.repository.jql.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Removes outdated Javers snapshots while preserving audit-history consistency.
 *
 * <h2>Snapshot promotion</h2>
 * UPDATE snapshots store only the changed property names in {@code changed_properties},
 * whereas an INITIAL snapshot must list all properties. When the original INITIAL
 * snapshot is deleted, the oldest remaining snapshot is promoted:
 * <ul>
 *   <li>{@code type} → {@code INITIAL}</li>
 *   <li>{@code changed_properties} → all property names from {@code state}</li>
 * </ul>
 * The {@code state} column always holds the complete object state and is left unchanged.
 *
 * <h2>Cross-entity reference protection (hybrid approach)</h2>
 * Javers stores entity references in snapshot {@code state} as
 * {@code {"entity": "pkg.ClassName", "cdoId": X}}. If a retained snapshot of entity A
 * references entity B, then B must have at least one snapshot with
 * {@code commitDate ≤ A.commitDate} — otherwise historical reconstruction of that
 * reference would use a later (incorrect) state of B. This applies to both entity
 * snapshots and Value Object snapshots (which may contain {@code @ManyToOne} fields).
 *
 * <p>The three-phase cleanup process:
 * <ol>
 *   <li><b>Phase 1</b> — compute proposed deletion sets per entity according to policy.</li>
 *   <li><b>Phase 2</b> — scan the {@code state} JSON of <em>all</em> retained snapshots
 *       (entities and VOs) for entity references; build a map of
 *       {@code globalIdPk → earliestReferenceCommitDate}.</li>
 *   <li><b>Phase 3</b> — for each referenced entity whose oldest retained snapshot would
 *       be newer than the earliest reference date, rescue the most recent snapshot that
 *       still predates that reference. Rescuing the snapshot at the <em>earliest</em>
 *       reference date implicitly covers all later references to the same entity.</li>
 * </ol>
 *
 * <p><b>Known limitation</b>: only direct references are protected. If a rescued
 * snapshot of entity B itself references entity C, C is not automatically re-evaluated.
 * Cascading reference chains are not handled.
 */
@Service
public class JaversCleanupService {

    private static final Logger log = LoggerFactory.getLogger(JaversCleanupService.class);

    private final Javers javers;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SnapshotPromoter promoter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JaversCleanupService(Javers javers,
                                JdbcTemplate jdbc,
                                NamedParameterJdbcTemplate namedJdbc,
                                SnapshotPromoter promoter) {
        this.javers = javers;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.promoter = promoter;
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

        // Phase 1: Compute proposed deletions for every entity.
        // Use mutable ArrayList so Phase 3 can remove rescued IDs.
        Map<Long, List<SnapshotRow>> allSnapshotsByGlobalId = new LinkedHashMap<>();
        Map<Long, List<Long>> deletionSetsByGlobalId = new LinkedHashMap<>();

        for (Long globalId : globalIds) {
            List<SnapshotRow> snapshots = loadSnapshots(globalId);
            allSnapshotsByGlobalId.put(globalId, snapshots);
            deletionSetsByGlobalId.put(globalId,
                    new ArrayList<>(policy.selectForDeletion(snapshots)));
        }

        // Phase 2: Scan ALL retained snapshots (entities and VOs) for cross-entity references.
        Map<Long, LocalDateTime> earliestRefDate =
                scanRetainedSnapshotsForReferences(allSnapshotsByGlobalId, deletionSetsByGlobalId);

        // Phase 3: Rescue anchor snapshots to preserve reference integrity.
        int rescued = rescueAnchorSnapshots(
                allSnapshotsByGlobalId, deletionSetsByGlobalId, earliestRefDate);

        // Execute deletions (deletion sets may have been trimmed by Phase 3).
        int promoted = 0;
        int deleted = 0;

        for (Long globalId : globalIds) {
            List<Long> toDelete = deletionSetsByGlobalId.get(globalId);
            if (toDelete.isEmpty()) continue;

            Set<Long> toDeleteSet = new HashSet<>(toDelete);
            List<SnapshotRow> remaining = allSnapshotsByGlobalId.get(globalId).stream()
                    .filter(s -> !toDeleteSet.contains(s.id()))
                    .toList(); // already sorted ascending by version

            if (!remaining.isEmpty()) {
                SnapshotRow oldestRemaining = remaining.getFirst();
                // Only UPDATE snapshots need promotion; INITIAL is already correct,
                // TERMINAL indicates a deleted entity and should not become INITIAL.
                if ("UPDATE".equals(oldestRemaining.type())) {
                    promoter.promoteToInitial(oldestRemaining);
                    promoted++;
                }
            }

            namedJdbc.update("DELETE FROM jv_snapshot WHERE snapshot_pk IN (:ids)",
                    Map.of("ids", toDelete));
            deleted += toDelete.size();

            log.debug("GlobalId {}: {} snapshot(s) deleted", globalId, toDelete.size());
        }

        int deletedCommits = cleanupOrphanedData();

        log.info("Cleanup complete: {} promoted, {} snapshots deleted, {} commits deleted, {} rescued",
                promoted, deleted, deletedCommits, rescued);

        return new CleanupResult(promoted, deleted, deletedCommits, rescued);
    }

    // -------------------------------------------------------------------------
    // Phase 2: reference scanning
    // -------------------------------------------------------------------------

    /**
     * Scans the {@code state} JSON of all retained snapshots (entities and VOs) for
     * entity references and returns the earliest {@code commitDate} at which each
     * referenced entity's {@code globalIdPk} appears as a reference target.
     */
    private Map<Long, LocalDateTime> scanRetainedSnapshotsForReferences(
            Map<Long, List<SnapshotRow>> allSnapshotsByGlobalId,
            Map<Long, List<Long>> deletionSetsByGlobalId) {

        // Include both entity snapshots and VO snapshots: VOs may contain @ManyToOne references.
        List<Long> retainedSnapshotIds = allSnapshotsByGlobalId.entrySet().stream()
                .flatMap(e -> {
                    Set<Long> toDelete = new HashSet<>(deletionSetsByGlobalId.get(e.getKey()));
                    return e.getValue().stream()
                            .filter(s -> !toDelete.contains(s.id()));
                })
                .map(SnapshotRow::id)
                .toList();

        if (retainedSnapshotIds.isEmpty()) return Map.of();

        record StateWithDate(LocalDateTime commitDate, String state) {}

        List<StateWithDate> retainedStates = namedJdbc.query("""
                SELECT c.commit_date, s.state
                FROM jv_snapshot s
                JOIN jv_commit c ON s.commit_fk = c.commit_pk
                WHERE s.snapshot_pk IN (:ids)
                """,
                Map.of("ids", retainedSnapshotIds),
                (rs, i) -> new StateWithDate(
                        rs.getTimestamp("commit_date").toLocalDateTime(),
                        rs.getString("state")
                ));

        // "typeName/localId" → global_id_pk for all entity global IDs in the DB
        Map<String, Long> entityRefToGlobalId = buildEntityRefLookup();

        Map<Long, LocalDateTime> result = new HashMap<>();
        for (StateWithDate s : retainedStates) {
            for (String ref : extractEntityRefs(s.state())) {
                Long globalIdPk = entityRefToGlobalId.get(ref);
                if (globalIdPk != null) {
                    result.merge(globalIdPk, s.commitDate(),
                            (existing, newDate) -> newDate.isBefore(existing) ? newDate : existing);
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Phase 3: anchor rescue
    // -------------------------------------------------------------------------

    /**
     * For each referenced entity whose oldest retained snapshot would be newer than
     * the earliest reference date, rescues the most recent snapshot that still
     * predates the reference. Modifies {@code deletionSetsByGlobalId} in-place.
     *
     * @return number of snapshots rescued
     */
    private int rescueAnchorSnapshots(
            Map<Long, List<SnapshotRow>> allSnapshotsByGlobalId,
            Map<Long, List<Long>> deletionSetsByGlobalId,
            Map<Long, LocalDateTime> earliestRefDate) {

        int rescued = 0;
        for (Map.Entry<Long, LocalDateTime> entry : earliestRefDate.entrySet()) {
            Long referencedGlobalId = entry.getKey();
            LocalDateTime refDate = entry.getValue();

            List<Long> toDelete = deletionSetsByGlobalId.get(referencedGlobalId);
            if (toDelete == null || toDelete.isEmpty()) continue;

            List<SnapshotRow> snapshots = allSnapshotsByGlobalId.get(referencedGlobalId);
            if (snapshots == null) continue;

            Set<Long> toDeleteSet = new HashSet<>(toDelete);

            // Check whether the oldest retained snapshot already covers the reference date.
            boolean alreadyCovered = snapshots.stream()
                    .filter(s -> !toDeleteSet.contains(s.id()))
                    .findFirst() // sorted ascending by version → oldest retained first
                    .map(oldest -> !oldest.commitDate().isAfter(refDate))
                    .orElse(false);

            if (alreadyCovered) continue;

            // Rescue: pick the most recent snapshot in the deletion set that still
            // predates the reference date. This single snapshot implicitly covers all
            // retained references to this entity (see class-level javadoc).
            boolean didRescue = snapshots.stream()
                    .filter(s -> toDeleteSet.contains(s.id()))
                    .filter(s -> !s.commitDate().isAfter(refDate))
                    .max(Comparator.comparing(SnapshotRow::commitDate))
                    .map(candidate -> {
                        toDelete.remove(candidate.id());
                        log.debug("Rescued snapshot {} (v{}, {}) for GlobalId {} — earliest ref at {}",
                                candidate.id(), candidate.version(),
                                candidate.typeName(), referencedGlobalId, refDate);
                        return true;
                    })
                    .orElse(false);

            if (didRescue) rescued++;
        }
        return rescued;
    }

    // -------------------------------------------------------------------------
    // JSON reference scanning helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a lookup map from {@code "typeName/normalizedLocalId"} to {@code global_id_pk}.
     *
     * <p>Uses {@link SnapshotPromoter#normalizeLocalId} to strip Javers' JSON-string quotes
     * from non-numeric IDs so the key matches what {@link #collectEntityRefs} produces via
     * {@code cdoIdNode.asText()}.
     */
    private Map<String, Long> buildEntityRefLookup() {
        return jdbc.query("""
                SELECT global_id_pk, type_name, local_id
                FROM jv_global_id
                WHERE owner_id_fk IS NULL
                """,
                (rs, i) -> Map.entry(
                        rs.getString("type_name") + "/" + SnapshotPromoter.normalizeLocalId(rs.getString("local_id")),
                        rs.getLong("global_id_pk")
                ))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Parses a snapshot {@code state} JSON and returns all entity references found
     * in the form {@code "typeName/localId"}.
     *
     * <p>Javers serializes entity references as
     * {@code {"entity": "pkg.ClassName", "cdoId": X}} nested at any depth.
     */
    private Set<String> extractEntityRefs(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) return Set.of();
        try {
            JsonNode root = objectMapper.readTree(stateJson);
            Set<String> refs = new LinkedHashSet<>();
            collectEntityRefs(root, refs);
            return refs;
        } catch (JsonProcessingException e) {
            log.warn("Could not parse snapshot state JSON for reference scanning: {}", e.getMessage());
            return Set.of();
        }
    }

    private void collectEntityRefs(JsonNode node, Set<String> refs) {
        if (node.isObject()) {
            JsonNode entityNode = node.get("entity");
            JsonNode cdoIdNode = node.get("cdoId");
            if (entityNode != null && cdoIdNode != null && entityNode.isTextual()) {
                // Leaf: this object IS an entity reference — add it and stop recursing.
                refs.add(entityNode.asText() + "/" + cdoIdNode.asText());
            } else {
                node.fields().forEachRemaining(e -> collectEntityRefs(e.getValue(), refs));
            }
        } else if (node.isArray()) {
            node.elements().forEachRemaining(e -> collectEntityRefs(e, refs));
        }
    }

    // -------------------------------------------------------------------------
    // Orphan cleanup
    // -------------------------------------------------------------------------

    private List<SnapshotRow> loadSnapshots(long globalId) {
        return jdbc.query("""
                SELECT s.snapshot_pk, s.version, s.type, s.changed_properties, s.commit_fk,
                       c.commit_date, g.type_name, g.local_id, g.fragment,
                       og.type_name AS owner_type_name, og.local_id AS owner_local_id
                FROM jv_snapshot s
                JOIN jv_commit c ON s.commit_fk = c.commit_pk
                JOIN jv_global_id g ON s.global_id_fk = g.global_id_pk
                LEFT JOIN jv_global_id og ON g.owner_id_fk = og.global_id_pk
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
                        rs.getString("local_id"),
                        rs.getString("fragment"),
                        rs.getString("owner_type_name"),
                        rs.getString("owner_local_id")
                ),
                globalId);
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
}
