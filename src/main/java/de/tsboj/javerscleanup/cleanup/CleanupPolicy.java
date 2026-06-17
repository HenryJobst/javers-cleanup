package de.tsboj.javerscleanup.cleanup;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bestimmt, welche Snapshots bei einem Cleanup-Lauf gelöscht werden dürfen.
 *
 * @param retentionDays      Snapshots älter als N Tage sind Löschkandidaten (0 = nur count-basiert)
 * @param minSnapshotsToKeep Mindestanzahl der neuesten Snapshots, die pro Entität erhalten bleiben
 */
public record CleanupPolicy(int retentionDays, int minSnapshotsToKeep) {

    public CleanupPolicy {
        if (minSnapshotsToKeep < 1) throw new IllegalArgumentException("minSnapshotsToKeep muss >= 1 sein");
        if (retentionDays < 0) throw new IllegalArgumentException("retentionDays muss >= 0 sein");
        if (retentionDays == 0 && minSnapshotsToKeep == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Policy würde nichts löschen");
    }

    /** Behält nur die letzten {@code count} Snapshots je Entität. */
    public static CleanupPolicy keepLatest(int count) {
        return new CleanupPolicy(0, count);
    }

    /** Löscht alles älter als {@code days} Tage, behält aber mindestens {@code minKeep} Snapshots. */
    public static CleanupPolicy olderThan(int days, int minKeep) {
        return new CleanupPolicy(days, minKeep);
    }

    /**
     * Wählt aus der nach Version aufsteigend sortierten Liste die zu löschenden Snapshot-IDs aus.
     * Die neuesten {@code minSnapshotsToKeep} Snapshots werden immer behalten.
     */
    List<Long> selectForDeletion(List<SnapshotRow> snapshotsSortedAsc) {
        int total = snapshotsSortedAsc.size();
        if (total <= minSnapshotsToKeep) return List.of();

        int maxToDelete = total - minSnapshotsToKeep;

        if (retentionDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            return snapshotsSortedAsc.stream()
                    .filter(s -> s.commitDate().isBefore(cutoff))
                    .limit(maxToDelete)
                    .map(SnapshotRow::id)
                    .toList();
        }

        // count-basiert: älteste löschen
        return snapshotsSortedAsc.subList(0, maxToDelete).stream()
                .map(SnapshotRow::id)
                .toList();
    }
}
