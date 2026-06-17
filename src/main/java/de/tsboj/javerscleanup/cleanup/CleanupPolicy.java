package de.tsboj.javerscleanup.cleanup;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Determines which snapshots are eligible for deletion in a cleanup run.
 *
 * @param retentionDays      snapshots older than N days are deletion candidates (0 = count-based only)
 * @param minSnapshotsToKeep minimum number of most-recent snapshots to retain per entity
 */
public record CleanupPolicy(int retentionDays, int minSnapshotsToKeep) {

    public CleanupPolicy {
        if (minSnapshotsToKeep < 1) throw new IllegalArgumentException("minSnapshotsToKeep must be >= 1");
        if (retentionDays < 0) throw new IllegalArgumentException("retentionDays must be >= 0");
        if (retentionDays == 0 && minSnapshotsToKeep == Integer.MAX_VALUE)
            throw new IllegalArgumentException("policy would delete nothing");
    }

    /** Keeps only the latest {@code count} snapshots per entity. */
    public static CleanupPolicy keepLatest(int count) {
        return new CleanupPolicy(0, count);
    }

    /** Deletes everything older than {@code days} days, but always keeps at least {@code minKeep} snapshots. */
    public static CleanupPolicy olderThan(int days, int minKeep) {
        return new CleanupPolicy(days, minKeep);
    }

    /**
     * Selects snapshot IDs to delete from the given list (sorted ascending by version).
     * The most-recent {@code minSnapshotsToKeep} snapshots are always preserved.
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

        // count-based: delete oldest
        return snapshotsSortedAsc.subList(0, maxToDelete).stream()
                .map(SnapshotRow::id)
                .toList();
    }
}
