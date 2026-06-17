package de.tsboj.javerscleanup.cleanup;

public record CleanupResult(
        int promotedSnapshots,
        int deletedSnapshots,
        int deletedCommits,
        int deletedGlobalIds
) {
    @Override
    public String toString() {
        return "CleanupResult{befördert=%d, gelöschteSnapshots=%d, gelöschteCommits=%d, gelöschteGlobalIds=%d}"
                .formatted(promotedSnapshots, deletedSnapshots, deletedCommits, deletedGlobalIds);
    }
}
