package io.github.henryjobst.javerscleanup;

public record CleanupResult(
        int promotedSnapshots,
        int deletedSnapshots,
        int deletedCommits,
        int rescuedSnapshots
) {
    @Override
    public String toString() {
        return "CleanupResult{promoted=%d, deletedSnapshots=%d, deletedCommits=%d, rescued=%d}"
                .formatted(promotedSnapshots, deletedSnapshots, deletedCommits, rescuedSnapshots);
    }
}
