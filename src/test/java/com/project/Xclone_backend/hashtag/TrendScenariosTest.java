package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendCandidate;

/**
 * Ten realistic situations for a 6-hour window with a 7-day baseline (28 slices), scored by the previous algorithm and by the current one.
 * The numbers are deterministic inputs, written the way the query delivers them: effective counts (limited per author, weighted by account age)
 * and per-slice baseline averages. The old formula is kept here only as the reference to compare against.
 */
class TrendScenariosTest {

    private static final double SMOOTHING = 3.0;
    private static final double WINDOW = 6, BASELINE = 168;

    /** What the old algorithm saw: plain counts, and all of the baseline's posts. */
    private record Raw(long recentAuthors, long newerHalfAuthors, long baselinePosts) {
    }

    private record Scenario(TrendCandidate candidate, Raw raw) {
    }

    /** The previous formula: (recentAuthors + newerHalfAuthors) / (1 + baselinePosts * window / baseline). */
    private static double oldScore(Raw r) {
        return (r.recentAuthors() + r.newerHalfAuthors()) / (1 + r.baselinePosts() * WINDOW / BASELINE);
    }

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    private static void add(String name, long rawPosts, long rawAuthors, double posts, double authors, double newer, double older, double expPosts,
            double expAuthors, long baselinePostsRaw) {
        add(name, rawPosts, rawAuthors, posts, authors, newer, older, expPosts, expAuthors, baselinePostsRaw, Math.round(newer));
    }

    /** {@code rawNewer}: the plain number of authors in the newer half, which differs from the weighted {@code newer} only for young accounts. */
    private static void add(String name, long rawPosts, long rawAuthors, double posts, double authors, double newer, double older, double expPosts,
            double expAuthors, long baselinePostsRaw, long rawNewer) {
        SCENARIOS.put(name, new Scenario(new TrendCandidate(name, rawPosts, rawAuthors, posts, authors, newer, older, expPosts, expAuthors),
                new Raw(rawAuthors, rawNewer, baselinePostsRaw)));
    }

    static {
        // name, raw posts, raw authors, | effective: posts, authors, newer-half authors, older-half authors | expected per window: posts, authors | raw baseline posts
        add("1 popular, stable",           62, 36, 62, 36, 22, 20, 60, 35, 60 * 28);
        add("2 small, rapid growth",       14, 10, 14, 10,  9,  3, 1.5, 1.2, 42);
        add("3 brand new, real interest",  18, 12, 18, 12,  8,  5, 0, 0, 0);
        add("4 brand new, tiny",            3,  2,  3,  2,  2,  0, 0, 0, 0);
        // One account posted 58 times and another twice: the limit of 3 posts per author leaves 3 + 1.
        add("5 one author repeating",      60,  2,  4,  2,  2,  1, 0, 0, 0);
        add("6 many independent authors",  30, 28, 30, 28, 18, 14, 5, 4.5, 140);
        add("7 declining",                 18, 11, 18, 11,  3,  9, 20, 12, 560);
        // 250 people, some posting more than three times; a normal week had about 3 posts per window.
        add("8 legitimate viral event",   400, 250, 330, 250, 220, 60, 3, 2.5, 84);
        add("9 sparse history",            10,  7, 10,  7,  5,  3, 0.04, 0.04, 1);
        // Twelve accounts one day old (weight 1/3 each), 3 to 6 posts each: effective 12 posts and 4 authors.
        add("10 coordinated new accounts", 54, 12, 12, 4, 4, 0, 0, 0, 0, 12);
    }

    private static double newScore(String name) {
        return TrendScorer.score(SCENARIOS.get(name).candidate(), SMOOTHING);
    }

    private static double old(String name) {
        return oldScore(SCENARIOS.get(name).raw());
    }

    private static List<String> rankedBy(java.util.function.ToDoubleFunction<String> score) {
        List<String> names = new ArrayList<>(SCENARIOS.keySet());
        names.sort(Comparator.comparingDouble(score).reversed().thenComparing(Comparator.naturalOrder()));
        return names;
    }

    @Test
    void theNewScoresAreExactlyAsDocumented() {
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("1 popular, stable", -0.38);
        expected.put("2 small, rapid growth", 1.13);
        expected.put("3 brand new, real interest", 1.58);
        expected.put("4 brand new, tiny", -0.25);
        expected.put("5 one author repeating", -0.39);
        expected.put("6 many independent authors", 1.47);
        expected.put("7 declining", -1.39);
        expected.put("8 legitimate viral event", 5.77);
        expected.put("9 sparse history", 0.82);
        expected.put("10 coordinated new accounts", 0.90);
        expected.forEach((name, score) -> assertThat(newScore(name)).as(name).isCloseTo(score, within(0.006)));
    }

    @Test
    void theOldScoresForTheSameScenarios() {
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("1 popular, stable", 0.95);
        expected.put("2 small, rapid growth", 7.60);
        expected.put("3 brand new, real interest", 20.00);
        expected.put("4 brand new, tiny", 4.00);
        expected.put("5 one author repeating", 4.00);
        expected.put("6 many independent authors", 7.67);
        expected.put("7 declining", 0.67);
        expected.put("8 legitimate viral event", 117.50);
        expected.put("9 sparse history", 11.59);
        expected.put("10 coordinated new accounts", 24.00);
        expected.forEach((name, score) -> assertThat(old(name)).as(name).isCloseTo(score, within(0.006)));
    }

    @Test
    void theRankingsAreAsExplained() {
        assertThat(rankedBy(TrendScenariosTest::newScore)).containsExactly(
                "8 legitimate viral event", "3 brand new, real interest", "6 many independent authors", "2 small, rapid growth",
                "10 coordinated new accounts", "9 sparse history", "4 brand new, tiny", "1 popular, stable", "5 one author repeating", "7 declining");
        assertThat(rankedBy(TrendScenariosTest::old)).containsExactly(
                "8 legitimate viral event", "10 coordinated new accounts", "3 brand new, real interest", "9 sparse history",
                "6 many independent authors", "2 small, rapid growth", "4 brand new, tiny", "5 one author repeating", "1 popular, stable", "7 declining");
    }

    @Test
    void aPopularStableTagDoesNotTrendAndADecliningOneIsLast() {
        assertThat(newScore("1 popular, stable")).isNegative();
        assertThat(rankedBy(TrendScenariosTest::newScore).get(SCENARIOS.size() - 1)).isEqualTo("7 declining");
        assertThat(newScore("7 declining")).isLessThan(newScore("1 popular, stable"));
    }

    @Test
    void aSmallTagGrowingFastBeatsABigOneThatIsOnlyNormal() {
        assertThat(newScore("2 small, rapid growth")).isGreaterThan(0.5);
        assertThat(newScore("2 small, rapid growth")).isGreaterThan(newScore("1 popular, stable") + 1);
    }

    @Test
    void aBrandNewTagCanTrendWithRealParticipationButNotWithAHandfulOfPosts() {
        assertThat(newScore("3 brand new, real interest")).isGreaterThan(1.0);
        assertThat(newScore("4 brand new, tiny")).isNegative();
        assertThat(newScore("3 brand new, real interest")).isGreaterThan(newScore("4 brand new, tiny") + 1.5);
    }

    @Test
    void oneAuthorRepeatingIsWorthLessThanManyIndependentAuthors() {
        assertThat(newScore("5 one author repeating")).isLessThan(newScore("4 brand new, tiny"));
        assertThat(newScore("6 many independent authors")).isGreaterThan(newScore("5 one author repeating") + 1.5);
    }

    @Test
    void aLegitimateViralEventStillTakesFirstPlace() {
        assertThat(rankedBy(TrendScenariosTest::newScore).get(0)).isEqualTo("8 legitimate viral event");
        assertThat(newScore("8 legitimate viral event")).isGreaterThan(newScore("6 many independent authors") + 3);
    }

    @Test
    void sparseHistoryIsTreatedLikeNoHistoryNotAsProofOfAnythingEither() {
        // One post last week and none otherwise changes almost nothing: the two cases differ by less than 0.01 of score.
        TrendCandidate none = new TrendCandidate("n", 10, 7, 10, 7, 5, 3, 0, 0);
        assertThat(newScore("9 sparse history")).isCloseTo(TrendScorer.score(none, SMOOTHING), within(0.02));
    }

    @Test
    void aBurstFromYoungAccountsNoLongerOutranksTheSameSizeOfRealParticipation() {
        // Old: the coordinated burst was second, ahead of a tag 7 established authors were using. New: it is below the viral event and
        // the two genuinely growing tags, and close to (not far above) an organic tag of similar size.
        assertThat(rankedBy(TrendScenariosTest::old).indexOf("10 coordinated new accounts")).isEqualTo(1);
        assertThat(rankedBy(TrendScenariosTest::newScore).indexOf("10 coordinated new accounts")).isEqualTo(4);
        assertThat(newScore("10 coordinated new accounts")).isLessThan(newScore("6 many independent authors"));
        assertThat(newScore("10 coordinated new accounts")).isLessThan(newScore("3 brand new, real interest"));
    }
}
