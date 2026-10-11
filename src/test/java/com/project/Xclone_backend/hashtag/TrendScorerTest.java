package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendCandidate;
import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;

class TrendScorerTest {

    private static final double S = 3.0; // the default smoothing

    /** All of a tag's scoring inputs; the raw counts reported by the API are derived from the effective ones. */
    private static TrendCandidate tag(String name, double posts, double authors, double newer, double older, double expPosts, double expAuthors) {
        return new TrendCandidate(name, Math.round(posts), Math.round(authors), posts, authors, newer, older, expPosts, expAuthors);
    }

    private static List<String> order(TrendCandidate... candidates) {
        return TrendScorer.rank(List.of(candidates), S, 50).stream().map(TrendingHashtag::name).toList();
    }

    // --- the building block: a conservative log growth rate ---

    @Test
    void activityMatchingExpectationIsZeroGrowthMinusTheUncertainty() {
        // ln(1) = 0, standard error sqrt(1/13 + 1/13): 10 against an expected 10, smoothing 3.
        assertThat(TrendScorer.liftLowerBound(10, 10, S)).isCloseTo(-Math.sqrt(2.0 / 13), within(1e-12));
    }

    @Test
    void activityAboveExpectationScoresHigherAndBelowScoresLower() {
        double matching = TrendScorer.liftLowerBound(10, 10, S);
        assertThat(TrendScorer.liftLowerBound(30, 10, S)).isGreaterThan(matching);
        assertThat(TrendScorer.liftLowerBound(4, 10, S)).isLessThan(matching);
        assertThat(TrendScorer.liftLowerBound(30, 10, S)).isGreaterThan(0);
        assertThat(TrendScorer.liftLowerBound(2, 40, S)).isLessThan(-1);
    }

    @Test
    void noHistoryGivesAFiniteScoreThatGrowsWithTheEvidence() {
        // 18 against nothing: ln(21/3) - sqrt(1/21 + 1/3)
        assertThat(TrendScorer.liftLowerBound(18, 0, S)).isCloseTo(Math.log(7) - Math.sqrt(1.0 / 21 + 1.0 / 3), within(1e-12));
        double three = TrendScorer.liftLowerBound(3, 0, S);
        double thirty = TrendScorer.liftLowerBound(30, 0, S);
        double threeHundred = TrendScorer.liftLowerBound(300, 0, S);
        assertThat(three).isLessThan(thirty);
        assertThat(thirty).isLessThan(threeHundred);
        assertThat(Double.isFinite(three) && Double.isFinite(threeHundred)).isTrue();
    }

    @Test
    void veryFewPostsAreNotEnoughToStandOutEvenAgainstNothing() {
        // One post against no history: ln(4/3) - sqrt(1/4 + 1/3) is negative, so a single post cannot look like a surge.
        assertThat(TrendScorer.liftLowerBound(1, 0, S)).isCloseTo(Math.log(4.0 / 3) - Math.sqrt(0.25 + 1.0 / 3), within(1e-12)).isNegative();
    }

    @Test
    void anExtremeBurstStaysFiniteAndOrdered() {
        double burst = TrendScorer.liftLowerBound(1_000_000, 5, S);
        assertThat(burst).isFinite().isBetween(Math.log(1_000_003.0 / 8) - 0.5, Math.log(1_000_003.0 / 8));
        assertThat(burst).isGreaterThan(TrendScorer.liftLowerBound(1_000, 5, S));
    }

    @Test
    void negativeOrMissingInputsAreTreatedAsZero() {
        assertThat(TrendScorer.liftLowerBound(-5, -5, S)).isEqualTo(TrendScorer.liftLowerBound(0, 0, S));
        assertThat(TrendScorer.liftLowerBound(0, 0, S)).isCloseTo(-Math.sqrt(2.0 / 3), within(1e-12));
    }

    // --- the three signals ---

    @Test
    void theScoreIsParticipationPlusHalfVolumePlusHalfMomentum() {
        TrendCandidate c = tag("x", 14, 10, 9, 3, 1.5, 1.2);
        double expected = TrendScorer.liftLowerBound(10, 1.2, S)
                + 0.5 * TrendScorer.liftLowerBound(14, 1.5, S)
                + 0.5 * TrendScorer.liftLowerBound(9, 3, 1.0);
        assertThat(TrendScorer.score(c, S)).isCloseTo(expected, within(1e-12));
    }

    @Test
    void aFadingTagScoresBelowAnOtherwiseIdenticalGrowingOne() {
        TrendCandidate growing = tag("up", 20, 12, 10, 4, 5, 4);
        TrendCandidate fading = tag("down", 20, 12, 4, 10, 5, 4);
        assertThat(TrendScorer.score(fading, S)).isLessThan(TrendScorer.score(growing, S));
        assertThat(TrendScorer.liftLowerBound(4, 10, 1.0)).isNegative();
        assertThat(order(fading, growing)).containsExactly("up", "down");
    }

    @Test
    void aSmallDifferenceBetweenTheHalvesIsNotTreatedAsAcceleration() {
        // 5 against 4: well within random fluctuation, so the momentum term must not be positive.
        assertThat(TrendScorer.liftLowerBound(5, 4, 1.0)).isNegative();
    }

    @Test
    void aSpikeBeatsATagThatIsAlwaysBusy() {
        // "always" has 40 people now, and normally about 38 per window. "breaking" has 10, from about 1.
        assertThat(order(tag("always", 42, 40, 22, 20, 40, 38), tag("breaking", 15, 10, 8, 3, 1, 1))).containsExactly("breaking", "always");
    }

    @Test
    void aBrandNewTagCanTrendOnceEnoughDifferentPeopleUseIt() {
        assertThat(order(tag("fresh", 18, 12, 8, 4, 0, 0), tag("meh", 3, 3, 2, 1, 40, 30))).containsExactly("fresh", "meh");
    }

    @Test
    void moreIndependentPeopleOutrankFewerWithTheSameVolume() {
        assertThat(order(tag("narrow", 12, 4, 3, 1, 0, 0), tag("broad", 12, 12, 6, 6, 0, 0))).containsExactly("broad", "narrow");
    }

    @Test
    void lowEvidenceDoesNotBeatHighEvidenceAtTheSameGrowth() {
        // Both grew about fourfold; the one with ten times the people is the more credible trend.
        assertThat(order(tag("small", 8, 4, 2, 2, 2, 1), tag("large", 80, 40, 20, 20, 20, 10))).containsExactly("large", "small");
    }

    // --- ranking ---

    @Test
    void equalScoresBreakByAuthorsThenPostsThenName() {
        TrendCandidate a = new TrendCandidate("a", 5, 3, 4, 3, 2, 1, 0, 0);
        TrendCandidate b = new TrendCandidate("b", 5, 3, 4, 3, 2, 1, 0, 0);
        TrendCandidate morePosts = new TrendCandidate("c", 9, 3, 4, 3, 2, 1, 0, 0);
        TrendCandidate moreAuthors = new TrendCandidate("d", 5, 4, 4, 3, 2, 1, 0, 0);
        assertThat(TrendScorer.score(a, S)).isEqualTo(TrendScorer.score(b, S)).isEqualTo(TrendScorer.score(morePosts, S));
        assertThat(order(b, a, morePosts, moreAuthors)).containsExactly("d", "c", "a", "b");
    }

    @Test
    void rankingIgnoresTheOrderTheCandidatesArriveIn() {
        List<TrendCandidate> all = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 60; i++) {
            all.add(tag("t" + i, random.nextInt(40), 1 + random.nextInt(20), random.nextInt(15), random.nextInt(15), random.nextInt(30), random.nextInt(20)));
        }
        List<TrendingHashtag> expected = TrendScorer.rank(all, S, 50);
        for (int round = 0; round < 5; round++) {
            Collections.shuffle(all, new Random(round));
            assertThat(TrendScorer.rank(all, S, 50)).isEqualTo(expected);
        }
    }

    @Test
    void theResultIsLimitedAndCarriesTheRecentCounts() {
        List<TrendingHashtag> top = TrendScorer.rank(List.of(tag("x", 7, 3, 2, 1, 0, 0), tag("y", 5, 2, 1, 1, 0, 0)), S, 1);
        assertThat(top).containsExactly(new TrendingHashtag("x", 7, 3));
    }

    @Test
    void anEmptyListAndAZeroLimitGiveNothing() {
        assertThat(TrendScorer.rank(List.of(), S, 10)).isEmpty();
        assertThat(TrendScorer.rank(List.of(tag("x", 7, 3, 2, 1, 0, 0)), S, 0)).isEmpty();
    }
}
