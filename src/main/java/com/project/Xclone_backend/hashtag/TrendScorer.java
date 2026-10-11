package com.project.Xclone_backend.hashtag;

import java.util.Comparator;
import java.util.List;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendCandidate;
import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;

/**
 * Ranks tags by how far their recent activity rises above what is normal for them, not by raw volume.
 *
 * <p>The score adds three signals that are kept apart on purpose, each a <em>conservative log growth rate</em> (see {@link #liftLowerBound}):
 * <pre>
 *   score = participation + VOLUME_WEIGHT * volume + MOMENTUM_WEIGHT * momentum
 *   participation = lift(authors,      expectedAuthors, smoothing)   how many more people than usual are using it
 *   volume        = lift(posts,        expectedPosts,   smoothing)   how many more posts than usual (an author counts at most N per window)
 *   momentum      = lift(newerAuthors, olderAuthors,    1)           is it still growing inside the window (negative when it is fading)
 * </pre>
 * Participation leads because distinct people are the hardest thing to fake; volume and momentum are supporting evidence and count half as much.
 * Authors and posts are never mixed in one ratio: every ratio compares a quantity with the same quantity's own normal level.
 * All inputs are per-slice averages / window totals prepared by the query, so scoring is a pure function of the candidate: O(1) per tag, and
 * ranking is O(c log c) for c candidates (a few hundred).
 */
final class TrendScorer {

    /**
     * How many standard errors the growth estimate is pulled down by, i.e. we rank by a lower bound that the true growth very likely exceeds
     * (about 84% one-sided for 1.0). Small counts have large standard errors, so thin evidence cannot rank high; large counts barely move.
     */
    static final double CONFIDENCE_Z = 1.0;
    /** Smoothing for the momentum ratio: the two halves of one window are small samples, so a little more shrinkage than 0 is needed. */
    static final double MOMENTUM_SMOOTHING = 1.0;
    static final double VOLUME_WEIGHT = 0.5;
    static final double MOMENTUM_WEIGHT = 0.5;

    private TrendScorer() {
    }

    /**
     * {@code ln((observed + s) / (expected + s)) - z * sqrt(1/(observed + s) + 1/(expected + s))}.
     *
     * <p>The first part is the log growth with {@code s} extra pseudo-counts on both sides: it is 0 when activity matches expectation, positive when
     * above, negative when below, and finite when {@code expected} is 0 (a new tag). The second part is the usual approximation of the standard
     * error of a log ratio of two counts (about {@code 1/count} each), scaled by {@code z}. Subtracting it makes the result a lower bound: 3
     * posts against nothing is weaker evidence than 300 against nothing, even though both are "infinitely" above expectation.
     */
    static double liftLowerBound(double observed, double expected, double smoothing) {
        double o = Math.max(0, observed) + smoothing;
        double e = Math.max(0, expected) + smoothing;
        return Math.log(o / e) - CONFIDENCE_Z * Math.sqrt(1 / o + 1 / e);
    }

    static double score(TrendCandidate c, double smoothing) {
        double participation = liftLowerBound(c.authors(), c.expectedAuthors(), smoothing);
        double volume = liftLowerBound(c.posts(), c.expectedPosts(), smoothing);
        double momentum = liftLowerBound(c.newerAuthors(), c.olderAuthors(), MOMENTUM_SMOOTHING);
        return participation + VOLUME_WEIGHT * volume + MOMENTUM_WEIGHT * momentum;
    }

    /** Scores are compared at 1e-9 so floating-point noise never decides an order; equal scores fall through to the tie-breakers. */
    private static long key(double score) {
        return Math.round(score * 1e9);
    }

    static List<TrendingHashtag> rank(List<TrendCandidate> candidates, double smoothing, int limit) {
        Comparator<TrendCandidate> order = Comparator.comparingLong((TrendCandidate c) -> key(score(c, smoothing))).reversed()
                .thenComparing(Comparator.comparingLong(TrendCandidate::recentAuthors).reversed())
                .thenComparing(Comparator.comparingLong(TrendCandidate::recentPosts).reversed())
                .thenComparing(TrendCandidate::name);
        return candidates.stream()
                .sorted(order)
                .limit(Math.max(0, limit))
                .map(c -> new TrendingHashtag(c.name(), c.recentPosts(), c.recentAuthors()))
                .toList();
    }
}
