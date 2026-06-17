package de.tsboj.javerscleanup.cleanup;

/**
 * @param newInitialSnapshots  newly created INITIAL snapshots (new entities)
 * @param newUpdateSnapshots   newly created UPDATE snapshots (existing entities that changed)
 * @param unchangedEntities    entities with no state change — no new snapshot created
 * @param correctedToInitial   UPDATE snapshots retroactively converted to INITIAL
 *                             (occurs when jv_global_id exists but no snapshots remain)
 */
public record MigrationResult(
        int newInitialSnapshots,
        int newUpdateSnapshots,
        int unchangedEntities,
        int correctedToInitial
) {
    @Override
    public String toString() {
        return "MigrationResult{newInitial=%d, newUpdate=%d, unchanged=%d, correctedToInitial=%d}"
                .formatted(newInitialSnapshots, newUpdateSnapshots, unchangedEntities, correctedToInitial);
    }
}
