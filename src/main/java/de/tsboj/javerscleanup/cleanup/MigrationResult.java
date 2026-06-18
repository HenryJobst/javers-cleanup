package de.tsboj.javerscleanup.cleanup;

/**
 * @param newInitialSnapshots  newly created INITIAL snapshots for entities (new entities;
 *                             Value Object snapshots are not counted separately)
 * @param newUpdateSnapshots   newly created UPDATE snapshots for entities (existing entities
 *                             that changed; Value Object snapshots are not counted separately)
 * @param unchangedEntities    entities with no state change — no new snapshot created
 * @param correctedToInitial   UPDATE snapshots retroactively converted to INITIAL
 *                             (occurs when a prior INITIAL was deleted without promotion)
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
