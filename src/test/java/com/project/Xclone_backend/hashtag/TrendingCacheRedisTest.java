package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.project.Xclone_backend.auth.EmailSender;
import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.json.JsonMapper;

/** Against a real Redis (localhost:6379); skipped when none is reachable (docker compose up -d redis). */
@SpringBootTest
@ActiveProfiles("test")
class TrendingCacheRedisTest {

    private static final List<TrendingHashtag> LIST = List.of(new TrendingHashtag("java", 12, 7), new TrendingHashtag("spring", 5, 3));
    /** Window lengths no other test uses, so the keys cannot clash with anything else in a shared Redis. */
    private static final int HOURS = 77, HOURS_FOR_SERVICE = 78, HOURS_FOR_TTL = 79;

    @Autowired JdbcTemplate jdbc;
    @Autowired HashtagRepository repository;
    @Autowired JsonMapper json;
    @MockitoBean EmailSender emailSender;
    @MockitoBean S3Client s3Client;
    @MockitoBean S3Presigner s3Presigner;

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        try {
            redis.getConnectionFactory().getConnection().ping();
        } catch (RuntimeException e) {
            factory.destroy();
            assumeTrue(false, "Redis not reachable");
        }
        clean();
    }

    @AfterEach
    void disconnect() {
        if (factory != null) {
            try {
                clean();
            } catch (RuntimeException ignored) {
                // Redis was never there
            }
            factory.destroy();
        }
    }

    private void clean() {
        for (int h : new int[] {HOURS, HOURS_FOR_SERVICE, HOURS_FOR_TTL}) {
            redis.delete(List.of(TrendingCache.KEY_PREFIX + h, TrendingCache.LOCK_PREFIX + h));
        }
    }

    @Test
    void aStoredListComesBackIntactAndIsGoneAfterTheTtl() throws Exception {
        TrendingCache cache = new TrendingCache(redis, json, Duration.ofSeconds(1));
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.getOrCompute(HOURS_FOR_TTL, () -> { calls.incrementAndGet(); return LIST; })).isEqualTo(LIST);
        assertThat(cache.getOrCompute(HOURS_FOR_TTL, () -> { calls.incrementAndGet(); return List.of(); })).isEqualTo(LIST);
        assertThat(calls).hasValue(1);
        Long ttl = redis.getExpire(TrendingCache.KEY_PREFIX + HOURS_FOR_TTL, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 1000L);
        assertThat(redis.hasKey(TrendingCache.LOCK_PREFIX + HOURS_FOR_TTL)).as("the lock is released").isFalse();

        Thread.sleep(1300);

        assertThat(redis.hasKey(TrendingCache.KEY_PREFIX + HOURS_FOR_TTL)).isFalse();
        assertThat(cache.getOrCompute(HOURS_FOR_TTL, () -> { calls.incrementAndGet(); return List.of(new TrendingHashtag("fresh", 1, 1)); }))
                .containsExactly(new TrendingHashtag("fresh", 1, 1));
        assertThat(calls).hasValue(2);
    }

    @Test
    void manyAtOnceComputeOnceAndAllGetTheSameList() throws Exception {
        TrendingCache cache = new TrendingCache(redis, json, Duration.ofSeconds(30));
        AtomicInteger calls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<TrendingHashtag>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> cache.getOrCompute(HOURS, () -> {
                    calls.incrementAndGet();
                    sleep(200); // a query that takes a moment, so the others really are waiting
                    return LIST;
                })));
            }
            for (Future<List<TrendingHashtag>> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(LIST);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(calls).as("only one caller ran the query").hasValue(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void theServiceServesTheCachedRankingUntilItExpiresAndSharesItAcrossLimits() {
        Instant now = Instant.parse("2041-09-01T12:00:00Z"); // a window no other test writes to
        Instant recent = now.minusSeconds(600);
        TrendingFixture f = new TrendingFixture(jdbc);
        TrendingCache cache = new TrendingCache(redis, json, Duration.ofSeconds(30));
        HashtagService service = new HashtagService(repository, TrendingProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC), cache);
        String first = "cachea" + UUID.randomUUID().toString().substring(0, 8);
        String later = "cacheb" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> people = f.matureUsers(3, now);
        f.post(people.get(0), recent, first);
        f.post(people.get(1), recent, first);

        List<String> before = service.trending(HOURS_FOR_SERVICE, 50).stream().map(TrendingHashtag::name).toList();
        assertThat(before).contains(first).doesNotContain(later);

        // A new tag appears; the cached list does not know yet.
        f.post(people.get(0), recent, later);
        f.post(people.get(2), recent, later);
        assertThat(service.trending(HOURS_FOR_SERVICE, 50).stream().map(TrendingHashtag::name)).doesNotContain(later);
        // A different limit is served from the same entry.
        assertThat(service.trending(HOURS_FOR_SERVICE, 1)).hasSize(1).isEqualTo(service.trending(HOURS_FOR_SERVICE, 50).subList(0, 1));

        // Once the entry is gone (here by hand, normally by the TTL) the ranking is fresh.
        redis.delete(TrendingCache.KEY_PREFIX + HOURS_FOR_SERVICE);
        assertThat(service.trending(HOURS_FOR_SERVICE, 50).stream().map(TrendingHashtag::name)).contains(first, later);
    }

    @Test
    void theCachedResponseMatchesTheUncachedOneExactly() {
        Instant now = Instant.parse("2041-10-01T12:00:00Z");
        TrendingFixture f = new TrendingFixture(jdbc);
        List<Long> people = f.matureUsers(5, now);
        for (int t = 0; t < 6; t++) {
            String tag = "same" + t + UUID.randomUUID().toString().substring(0, 6);
            for (int a = 0; a <= t % 4 + 1 && a < people.size(); a++) {
                f.post(people.get(a), now.minusSeconds(300 + a), tag);
            }
        }
        HashtagService plain = new HashtagService(repository, TrendingProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC));
        HashtagService cached = new HashtagService(repository, TrendingProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC),
                new TrendingCache(redis, json, Duration.ofSeconds(30)));
        try {
            List<TrendingHashtag> expected = plain.trending(HOURS_FOR_TTL, 50);
            assertThat(cached.trending(HOURS_FOR_TTL, 50)).isEqualTo(expected);  // computed and stored
            assertThat(cached.trending(HOURS_FOR_TTL, 50)).isEqualTo(expected);  // read back from Redis
            assertThat(expected).isNotEmpty();
        } finally {
            redis.delete(TrendingCache.KEY_PREFIX + HOURS_FOR_TTL);
        }
    }
}
