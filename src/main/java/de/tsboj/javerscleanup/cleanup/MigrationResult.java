package de.tsboj.javerscleanup.cleanup;

/**
 * @param newInitialSnapshots   Neu erstellte INITIAL-Snapshots (neue Entitäten)
 * @param newUpdateSnapshots    Neu erstellte UPDATE-Snapshots (bestehende geänderte Entitäten)
 * @param unchangedEntities     Entitäten ohne Zustandsänderung → kein neuer Snapshot
 * @param correctedToInitial    UPDATE-Snapshots die nachträglich zu INITIAL korrigiert wurden
 *                              (tritt auf wenn jv_global_id existiert, aber keine Snapshots)
 */
public record MigrationResult(
        int newInitialSnapshots,
        int newUpdateSnapshots,
        int unchangedEntities,
        int correctedToInitial
) {
    @Override
    public String toString() {
        return ("MigrationResult{newInitial=%d, newUpdate=%d, unchanged=%d, correctedToInitial=%d}")
                .formatted(newInitialSnapshots, newUpdateSnapshots, unchangedEntities, correctedToInitial);
    }
}
