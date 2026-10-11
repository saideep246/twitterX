package com.project.Xclone_backend.hashtag;

public final class HashtagDtos {

    private HashtagDtos() {
    }

    /** {@code name} is lowercase without the leading '#'. */
    public record TrendingHashtag(String name, long postCount, long userCount) {
    }

    /**
     * One tag's activity for trending, before it is scored. {@code recentPosts} and {@code recentAuthors} are the plain counts in the recent window
     * (what the API reports). The rest are what scoring uses, each already limited per author and weighted by account age (see {@link TrendScorer}):
     * {@code posts} and {@code authors} in the recent window, {@code newerAuthors} / {@code olderAuthors} in its newer and older half, and
     * {@code expectedPosts} / {@code expectedAuthors}, the averages over equal-length slices of the baseline before the window (0 for a tag
     * with no history).
     */
    public record TrendCandidate(String name, long recentPosts, long recentAuthors, double posts, double authors, double newerAuthors,
            double olderAuthors, double expectedPosts, double expectedAuthors) {
    }
}
