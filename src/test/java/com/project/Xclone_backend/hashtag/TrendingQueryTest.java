package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.project.Xclone_backend.auth.EmailSender;
import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;
import com.project.Xclone_backend.hashtag.HashtagRepository.TrendRow;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The trending query against PostgreSQL, with explicit timestamps and a fixed clock. Everything happens around the year 2040, so no real post made
 * by another test can fall inside these windows, and the real clock plays no part. Each test also has a window of its own.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrendingQueryTest {

    private static final Instant BASE = Instant.parse("2040-06-15T12:00:00Z");
    private static final java.util.concurrent.atomic.AtomicInteger TESTS = new java.util.concurrent.atomic.AtomicInteger();
    private static final long W = 6 * 3600; // seconds in the default 6-hour window
    private static final int SLICES = 28;

    /** Every test gets its own "now", 30 days apart (more than the 8 days a query looks at), so tests cannot see each other's data. */
    private final Instant NOW = BASE.plus(Duration.ofDays(30L * TESTS.getAndIncrement()));
    private final Instant RECENT = NOW.minusSeconds(W);
    private final Instant HALF = NOW.minusSeconds(W / 2);
    private final Instant SINCE = RECENT.minusSeconds(SLICES * W);

    @Autowired JdbcTemplate jdbc;
    @Autowired HashtagRepository repository;
    @MockitoBean EmailSender emailSender;
    @MockitoBean S3Client s3Client;
    @MockitoBean S3Presigner s3Presigner;

    private TrendingFixture fixture() {
        return new TrendingFixture(jdbc);
    }

    private static String tag(String what) {
        return what + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private long mature(TrendingFixture f) {
        return f.user(NOW.minus(Duration.ofDays(90)));
    }

    private List<TrendRow> rows(int cap, double matureDays, long minAuthors) {
        return repository.findTrendRows(SINCE, RECENT, HALF, NOW, W, cap, matureDays, 0.25, 3.0, SLICES, minAuthors, 500);
    }

    private TrendRow row(String name) {
        return rows(3, 3, 2).stream().filter(r -> r.getName().equals(name)).findFirst().orElse(null);
    }

    private HashtagService service(TrendingProperties props) {
        return new HashtagService(repository, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Two established accounts posting once in the recent window, so a tag is eligible; returns nothing else about the tag. */
    private void makeEligible(TrendingFixture f, String tag) {
        f.post(mature(f), NOW.minusSeconds(60), tag);
        f.post(mature(f), NOW.minusSeconds(120), tag);
    }

    // --- time boundaries ---

    @Test
    void theRecentWindowIncludesItsStartAndTheCurrentMomentAndNothingLater() {
        TrendingFixture f = fixture();
        String tag = tag("win");
        f.post(mature(f), RECENT, tag);                       // exactly at the start: recent
        f.post(mature(f), NOW, tag);                          // exactly now: recent
        f.post(mature(f), RECENT.minusSeconds(1), tag);       // one second before: the newest baseline slice
        f.post(mature(f), NOW.plusSeconds(1), tag);           // after now: ignored

        TrendRow r = row(tag);

        assertThat(r.getRecentPosts()).isEqualTo(2);
        assertThat(r.getRecentAuthors()).isEqualTo(2);
        assertThat(r.getBaseAuthorsSum()).isEqualTo(1.0);
        assertThat(r.getBasePostsSum()).isEqualTo(1.0);
    }

    @Test
    void theNewerHalfStartsExactlyAtTheMiddleOfTheWindow() {
        TrendingFixture f = fixture();
        String tag = tag("half");
        f.post(mature(f), HALF, tag);                         // exactly in the middle: newer
        f.post(mature(f), HALF.minusSeconds(1), tag);         // one second earlier: older
        f.post(mature(f), RECENT, tag);                       // start of the window: older

        TrendRow r = row(tag);

        assertThat(r.getRecentAuthors()).isEqualTo(3);
        assertThat(r.getNewerAuthors()).isEqualTo(1.0);
        assertThat(r.getOlderAuthors()).isEqualTo(2.0);
    }

    @Test
    void baselineSlicesAreHalfOpenAndTheBaselineEndsExactlyAtItsStart() {
        TrendingFixture f = fixture();
        String tag = tag("base");
        makeEligible(f, tag);
        long x = mature(f);
        f.post(x, RECENT.minusSeconds(W), tag);               // exactly one window back: still slice 0 (a slice includes its start)
        f.post(x, RECENT.minusSeconds(W + 1), tag);           // one second earlier: slice 1
        f.post(x, SINCE, tag);                                // the very start of the baseline: the oldest slice, included
        f.post(x, SINCE.minusSeconds(1), tag);                // before the baseline: ignored

        TrendRow r = row(tag);

        // The same person in three different slices counts once in each: distinct authors are per slice, never per week.
        assertThat(r.getBaseAuthorsSum()).isEqualTo(3.0);
        assertThat(r.getBasePostsSum()).isEqualTo(3.0);
    }

    // --- authors and posts ---

    @Test
    void anAuthorActiveInBothHalvesCountsInBothAndOnceInTheWindow() {
        TrendingFixture f = fixture();
        String tag = tag("both");
        long a = mature(f);
        long b = mature(f);
        f.post(a, HALF.minusSeconds(3600), tag);
        f.post(a, NOW, tag);
        f.post(b, HALF.minusSeconds(60), tag);

        TrendRow r = row(tag);

        assertThat(r.getRecentPosts()).isEqualTo(3);
        assertThat(r.getRecentAuthors()).isEqualTo(2);
        assertThat(r.getNewerAuthors()).isEqualTo(1.0);
        assertThat(r.getOlderAuthors()).isEqualTo(2.0);
        assertThat(r.getAuthors()).isEqualTo(2.0);
    }

    @Test
    void oneAuthorRepeatingCountsForAtMostThePerAuthorLimit() {
        TrendingFixture f = fixture();
        String tag = tag("cap");
        long spammer = mature(f);
        long other = mature(f);
        for (int i = 0; i < 10; i++) {
            f.post(spammer, NOW.minusSeconds(100 + i), tag);
        }
        f.post(other, NOW.minusSeconds(50), tag);
        for (int i = 0; i < 10; i++) {                         // and in one baseline slice as well
            f.post(spammer, RECENT.minusSeconds(60 + i), tag);
        }

        TrendRow r = row(tag);

        assertThat(r.getRecentPosts()).isEqualTo(11);            // the API still reports what really happened
        assertThat(r.getPosts()).isEqualTo(3.0 + 1.0);           // but scoring sees 3 + 1
        assertThat(r.getBasePostsSum()).isEqualTo(3.0);
        assertThat(rows(1, 3, 2).stream().filter(x -> x.getName().equals(tag)).findFirst().orElseThrow().getPosts()).isEqualTo(2.0);
    }

    @Test
    void youngerAccountsCountForLessAndTheDiscountCanBeSwitchedOff() {
        TrendingFixture f = fixture();
        String tag = tag("age");
        f.post(f.user(NOW), NOW.minusSeconds(10), tag);                              // created this moment: weight 0.25
        f.post(f.user(NOW.minus(Duration.ofHours(36))), NOW.minusSeconds(10), tag);  // 1.5 of 3 days: weight 0.5
        f.post(f.user(NOW.minus(Duration.ofDays(3))), NOW.minusSeconds(10), tag);    // exactly mature: weight 1
        f.post(mature(f), NOW.minusSeconds(10), tag);                                // weight 1

        assertThat(row(tag).getAuthors()).isCloseTo(0.25 + 0.5 + 1 + 1, within(1e-9));
        assertThat(row(tag).getPosts()).isCloseTo(0.25 + 0.5 + 1 + 1, within(1e-9));
        TrendRow off = rows(3, 0, 2).stream().filter(x -> x.getName().equals(tag)).findFirst().orElseThrow();
        assertThat(off.getAuthors()).isEqualTo(4.0);
    }

    @Test
    void deletedPostsProtectedAndInactiveAuthorsAreLeftOut() {
        TrendingFixture f = fixture();
        String tag = tag("excl");
        f.post(mature(f), NOW.minusSeconds(10), tag);
        f.post(mature(f), NOW.minusSeconds(10), tag);
        long hidden = mature(f);
        f.setProtected(hidden, true);
        f.post(hidden, NOW.minusSeconds(10), tag);
        long gone = mature(f);
        f.post(gone, NOW.minusSeconds(10), tag);
        f.setStatus(gone, "DEACTIVATED");
        f.delete(f.post(mature(f), NOW.minusSeconds(10), tag));

        TrendRow r = row(tag);

        assertThat(r.getRecentAuthors()).isEqualTo(2);
        assertThat(r.getRecentPosts()).isEqualTo(2);
    }

    @Test
    void aTagNeedsTheMinimumNumberOfDifferentAuthors() {
        TrendingFixture f = fixture();
        String solo = tag("solo"), pair = tag("pair");
        long a = mature(f);
        f.post(a, NOW.minusSeconds(10), solo);
        f.post(a, NOW.minusSeconds(20), solo);
        f.post(a, NOW.minusSeconds(30), pair);
        f.post(mature(f), NOW.minusSeconds(10), pair);

        assertThat(rows(3, 3, 2)).extracting(TrendRow::getName).contains(pair).doesNotContain(solo);
        assertThat(rows(3, 3, 3)).extracting(TrendRow::getName).doesNotContain(pair, solo);
    }

    @Test
    void aPostWithSeveralTagsCountsOnceForEachAndAHashtagWrittenTwiceCountsOnce() {
        TrendingFixture f = fixture();
        String one = tag("dupa"), two = tag("dupb");
        long a = mature(f);
        long b = mature(f);
        f.post(a, NOW.minusSeconds(10), one, two);
        f.post(b, NOW.minusSeconds(10), one, two);

        assertThat(row(one).getRecentPosts()).isEqualTo(2);
        assertThat(row(two).getRecentPosts()).isEqualTo(2);
        // The same tag typed three times in one post is one tag (existing behaviour, relied on here).
        assertThat(HashtagService.extract("#Java #java #JAVA and #java")).containsExactly("java");
    }

    // --- the service: windows, limits, ordering ---

    @Test
    void theWindowLengthDecidesWhichPostsAreRecent() {
        TrendingFixture f = fixture();
        String tag = tag("dur");
        long a = mature(f);
        long b = mature(f);
        for (long who : new long[] {a, b}) {
            f.post(who, NOW.minus(Duration.ofMinutes(30)), tag);
            f.post(who, NOW.minus(Duration.ofHours(3)), tag);
            f.post(who, NOW.minus(Duration.ofHours(30)), tag);
        }
        HashtagService service = service(TrendingProperties.defaults());

        assertThat(count(service.trending(1, 50), tag)).isEqualTo(2);
        assertThat(count(service.trending(6, 50), tag)).isEqualTo(4);
        assertThat(count(service.trending(24, 50), tag)).isEqualTo(4);
        assertThat(count(service.trending(48, 50), tag)).isEqualTo(6);
        assertThat(count(service.trending(168, 50), tag)).isEqualTo(6);
        // Missing, zero and negative mean the default of 6 hours; more than a week is cut to a week.
        assertThat(count(service.trending(null, 50), tag)).isEqualTo(4);
        assertThat(count(service.trending(0, 50), tag)).isEqualTo(4);
        assertThat(count(service.trending(-3, 50), tag)).isEqualTo(4);
        assertThat(count(service.trending(100_000, 50), tag)).isEqualTo(6);
    }

    private static long count(List<TrendingHashtag> list, String tag) {
        return list.stream().filter(t -> t.name().equals(tag)).mapToLong(TrendingHashtag::postCount).findFirst().orElse(-1);
    }

    @Test
    void theResultLimitIsHonouredAndClamped() {
        TrendingFixture f = fixture();
        for (int i = 0; i < 4; i++) {
            makeEligible(f, tag("lim"));
        }
        HashtagService service = service(TrendingProperties.defaults());

        assertThat(service.trending(6, 2)).hasSize(2);
        assertThat(service.trending(6, null).size()).isLessThanOrEqualTo(HashtagService.TRENDING_DEFAULT_LIMIT);
        assertThat(service.trending(6, 1000).size()).isLessThanOrEqualTo(HashtagService.TRENDING_MAX_LIMIT);
    }

    @Test
    void nothingTrendsWhenThereIsNoActivityAtAll() {
        HashtagService service = new HashtagService(repository, TrendingProperties.defaults(),
                Clock.fixed(NOW.plus(Duration.ofDays(3650)), ZoneOffset.UTC));
        assertThat(service.trending(6, 50)).isEmpty();
    }

    @Test
    void aTagThatIsNewButBackedByManyDifferentPeopleOutranksOneThatIsAlwaysBusy() {
        TrendingFixture f = fixture();
        List<Long> crowd = f.matureUsers(10, NOW);
        String busy = tag("busy"), fresh = tag("fresh"), repeat = tag("repeat");
        for (int s = 1; s <= SLICES; s++) {                    // the busy tag: all ten people, every single slice, and now
            for (long a : crowd) {
                f.post(a, RECENT.minusSeconds(s * W - 100), busy);
            }
        }
        for (long a : crowd) {
            f.post(a, NOW.minusSeconds(100), busy);
        }
        for (int i = 0; i < 8; i++) {                          // the fresh tag: eight people, only now
            f.post(crowd.get(i), NOW.minusSeconds(200), fresh);
        }
        for (int i = 0; i < 60; i++) {                         // two accounts shouting
            f.post(crowd.get(i % 2), NOW.minusSeconds(10 + i), repeat);
        }

        List<String> names = service(TrendingProperties.defaults()).trending(6, 50).stream().map(TrendingHashtag::name).toList();

        assertThat(names).contains(busy, fresh, repeat);
        assertThat(names.indexOf(fresh)).isLessThan(names.indexOf(busy));
        assertThat(names.indexOf(fresh)).isLessThan(names.indexOf(repeat));
    }

    // --- candidate selection ---

    @Test
    void aRealSpikeIsNotCrowdedOutByHundredsOfBusyTagsThatAreSimplyNormal() {
        String run = UUID.randomUUID().toString().substring(0, 6);
        List<Long> authors = new TrendingFixture(jdbc).matureUsers(6, NOW);
        // 505 tags that always have 6 people per slice (and now): more authors than the spike has, but nothing unusual.
        jdbc.update("insert into hashtags (name) select 'crowd" + run + "' || g from generate_series(1, 505) g on conflict do nothing");
        Long[] ids = authors.toArray(new Long[0]);
        jdbc.update("""
                with p as (
                  insert into posts (author_id, content, like_count, reply_count, repost_count, deleted, created_at)
                  select a, 'crowd' || ? || g, 0, 0, 0, false,
                         case when s = 0 then ?::timestamptz - make_interval(secs => 100 + a % 7)
                              when s = -1 then ?::timestamptz - make_interval(secs => 5000 + a % 7)
                              else ?::timestamptz - make_interval(secs => s * ? - 100 - a % 7) end
                  from generate_series(1, 505) g, unnest(?::bigint[]) a, generate_series(-1, ?) s
                  returning id, content
                )
                insert into post_hashtags (hashtag_id, post_id) select h.id, p.id from p join hashtags h on h.name = p.content
                """, run, Timestamp.from(NOW), Timestamp.from(HALF), Timestamp.from(RECENT), W, ids, SLICES);
        String spike = tag("spike");
        for (int i = 0; i < 3; i++) {
            new TrendingFixture(jdbc).post(authors.get(i), NOW.minusSeconds(300 + i), spike);
        }
        assertThat(rows(3, 3, 2)).hasSizeLessThanOrEqualTo(HashtagService.TRENDING_CANDIDATES);
        List<String> names = service(TrendingProperties.defaults()).trending(6, 50).stream().map(TrendingHashtag::name).toList();

        assertThat(names).contains(spike);
        assertThat(names.get(0)).isEqualTo(spike);
    }

    // --- the SQL against an independent implementation of the same rules ---

    @Test
    void theQueryAgreesWithAPlainJavaImplementationOnRandomData() {
        TrendingFixture f = fixture();
        Random random = new Random(20240615);
        String run = UUID.randomUUID().toString().substring(0, 6);
        int users = 40, tags = 25, posts = 1200;
        List<Long> userIds = new ArrayList<>();
        Map<Long, Long> userAgeSeconds = new HashMap<>();
        Set<Long> excluded = new HashSet<>();
        for (int i = 0; i < users; i++) {
            long age = random.nextInt(8 * 86400);
            long id = f.user(NOW.minusSeconds(age));
            userIds.add(id);
            userAgeSeconds.put(id, age);
            if (random.nextInt(12) == 0) {
                f.setProtected(id, true);
                excluded.add(id);
            } else if (random.nextInt(12) == 0) {
                f.setStatus(id, "DEACTIVATED");
                excluded.add(id);
            }
        }
        record Post(long author, long at, List<String> tags, boolean deleted) {
        }
        List<Post> all = new ArrayList<>();
        for (int i = 0; i < posts; i++) {
            long author = userIds.get(random.nextInt(users));
            // Mostly inside the baseline and window, some beyond both ends, and many exactly on a whole hour so boundaries are hit.
            long at = SINCE.getEpochSecond() - 7200 + random.nextLong(SLICES * W + W + 2 * 7200 + 3600);
            if (random.nextInt(3) == 0) {
                at = at - at % 3600;
            }
            List<String> t = new ArrayList<>();
            for (int k = 1 + random.nextInt(3); k > 0; k--) {
                String name = "rnd" + run + random.nextInt(tags);
                if (!t.contains(name)) {
                    t.add(name);
                }
            }
            boolean deleted = random.nextInt(25) == 0;
            long id = f.post(author, Instant.ofEpochSecond(at), t.toArray(String[]::new));
            if (deleted) {
                f.delete(id);
            }
            all.add(new Post(author, at, t, deleted));
        }

        // The reference: the same rules, written the plain way.
        Map<String, Map<Long, long[]>> recent = new HashMap<>();             // tag -> author -> {posts, newer-half posts}
        Map<String, Map<String, Integer>> cells = new HashMap<>();            // tag -> "slice|author" -> posts
        for (Post p : all) {
            if (p.deleted() || excluded.contains(p.author()) || p.at() < SINCE.getEpochSecond() || p.at() > NOW.getEpochSecond()) {
                continue;
            }
            for (String name : p.tags()) {
                if (p.at() >= RECENT.getEpochSecond()) {
                    long[] c = recent.computeIfAbsent(name, k -> new HashMap<>()).computeIfAbsent(p.author(), k -> new long[2]);
                    c[0]++;
                    if (p.at() >= HALF.getEpochSecond()) {
                        c[1]++;
                    }
                } else {
                    long slice = (RECENT.getEpochSecond() - p.at() + W - 1) / W - 1; // ceil(distance / W) - 1
                    cells.computeIfAbsent(name, k -> new HashMap<>()).merge(slice + "|" + p.author(), 1, Integer::sum);
                }
            }
        }
        Map<String, TrendRow> actual = new HashMap<>();
        rows(3, 3, 2).stream().filter(r -> r.getName().startsWith("rnd" + run)).forEach(r -> actual.put(r.getName(), r));

        int compared = 0;
        for (Map.Entry<String, Map<Long, long[]>> e : recent.entrySet()) {
            String name = e.getKey();
            if (e.getValue().size() < 2) {
                assertThat(actual).as("%s has fewer than 2 authors", name).doesNotContainKey(name);
                continue;
            }
            double rawPosts = 0, eff = 0, authors = 0, newer = 0, older = 0;
            for (Map.Entry<Long, long[]> a : e.getValue().entrySet()) {
                double w = weight(userAgeSeconds.get(a.getKey()));
                rawPosts += a.getValue()[0];
                eff += w * Math.min(a.getValue()[0], 3);
                authors += w;
                newer += a.getValue()[1] > 0 ? w : 0;
                older += a.getValue()[1] < a.getValue()[0] ? w : 0;
            }
            double basePosts = 0, baseAuthors = 0;
            for (Map.Entry<String, Integer> cell : cells.getOrDefault(name, Map.of()).entrySet()) {
                double w = weight(userAgeSeconds.get(Long.parseLong(cell.getKey().substring(cell.getKey().indexOf('|') + 1))));
                basePosts += w * Math.min(cell.getValue(), 3);
                baseAuthors += w;
            }
            TrendRow r = actual.get(name);
            assertThat(r).as(name).isNotNull();
            assertThat(r.getRecentPosts()).as(name + " posts").isEqualTo((long) rawPosts);
            assertThat(r.getRecentAuthors()).as(name + " authors").isEqualTo(e.getValue().size());
            assertThat(r.getPosts()).as(name + " effective posts").isCloseTo(eff, within(1e-6));
            assertThat(r.getAuthors()).as(name + " effective authors").isCloseTo(authors, within(1e-6));
            assertThat(r.getNewerAuthors()).as(name + " newer").isCloseTo(newer, within(1e-6));
            assertThat(r.getOlderAuthors()).as(name + " older").isCloseTo(older, within(1e-6));
            assertThat(r.getBasePostsSum()).as(name + " baseline posts").isCloseTo(basePosts, within(1e-6));
            assertThat(r.getBaseAuthorsSum()).as(name + " baseline authors").isCloseTo(baseAuthors, within(1e-6));
            compared++;
        }
        assertThat(compared).as("the random data should exercise plenty of tags").isGreaterThan(10);
    }

    private static double weight(long ageSeconds) {
        return Math.max(0.25, Math.min(1.0, ageSeconds / (86400.0 * 3)));
    }
}
