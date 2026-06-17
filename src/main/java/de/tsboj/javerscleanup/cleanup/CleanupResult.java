package de.tsboj.javerscleanup.cleanup;

public record CleanupResult(
        int promotedSnapshots,
        int deletedSnapshots,
        int deletedCommits,
        int deletedGlobalIds
) {
    @Override
    public String toString() {
        return "CleanupResult{promoted=%d, deletedSnapshots=%d, deletedCommits=%d, deletedGlobalIds=%d}"
                .formatted(promotedSnapshots, deletedSnapshots, deletedCommits, deletedGlobalIds);
    }
}
