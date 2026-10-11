package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;

import tools.jackson.databind.json.JsonMapper;

class TrendingCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final List<TrendingHashtag> LIST = List.of(new TrendingHashtag("java", 12, 7), new TrendingHashtag("spring", 5, 3));
    private static final String JSON = "[{\"name\":\"java\",\"postCount\":12,\"userCount\":7},{\"name\":\"spring\",\"postCount\":5,\"userCount\":3}]";

    private final JsonMapper json = JsonMapper.builder().build();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private TrendingCache cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new TrendingCache(redis, json, TTL);
    }

    private static AtomicInteger counter() {
        return new AtomicInteger();
    }

    @Test
    void aTtlOfZeroNeverTouchesRedis() {
        TrendingCache off = new TrendingCache(redis, json, Duration.ZERO);
        AtomicInteger calls = counter();

        assertThat(off.getOrCompute(6, () -> { calls.incrementAndGet(); return LIST; })).isEqualTo(LIST);
        assertThat(off.getOrCompute(6, () -> { calls.incrementAndGet(); return LIST; })).isEqualTo(LIST);

        assertThat(calls).hasValue(2);
        verifyNoInteractions(redis);
        assertThat(TrendingCache.disabled().getOrCompute(6, () -> LIST)).isEqualTo(LIST);
    }

    @Test
    void aFreshEntryIsReturnedWithoutComputing() {
        when(values.get("trending:v1:6")).thenReturn(JSON);
        AtomicInteger calls = counter();

        List<TrendingHashtag> result = cache.getOrCompute(6, () -> { calls.incrementAndGet(); return List.of(); });

        assertThat(result).isEqualTo(LIST);
        assertThat(calls).hasValue(0);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aMissComputesStoresWithTheTtlAndReleasesTheLock() {
        when(values.get("trending:v1:6")).thenReturn(null);
        when(values.setIfAbsent(eq("trending:lock:v1:6"), anyString(), any(Duration.class))).thenReturn(true);
        AtomicInteger calls = counter();

        List<TrendingHashtag> result = cache.getOrCompute(6, () -> { calls.incrementAndGet(); return LIST; });

        assertThat(result).isEqualTo(LIST);
        assertThat(calls).hasValue(1);
        verify(values).set("trending:v1:6", JSON, TTL);
        verify(redis).delete("trending:lock:v1:6");
    }

    @Test
    void theEntryIsKeyedByTheWindowLengthOnly() {
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        cache.getOrCompute(6, () -> LIST);
        cache.getOrCompute(24, () -> LIST);

        verify(values).set(eq("trending:v1:6"), anyString(), eq(TTL));
        verify(values).set(eq("trending:v1:24"), anyString(), eq(TTL));
    }

    @Test
    void whenSomeoneElseIsComputingTheResultIsTakenFromThemInsteadOfComputingAgain() {
        when(values.get("trending:v1:6")).thenReturn(null, null, JSON); // miss, still nothing after the first wait, then their result
        when(values.setIfAbsent(eq("trending:lock:v1:6"), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger calls = counter();

        List<TrendingHashtag> result = cache.getOrCompute(6, () -> { calls.incrementAndGet(); return List.of(); });

        assertThat(result).isEqualTo(LIST);
        assertThat(calls).hasValue(0);
        verify(redis, never()).delete(anyString()); // it does not own the lock, so it must not release it
    }

    @Test
    void ifTheOtherCallerNeverDeliversItComputesItselfAndDoesNotReleaseTheirLock() {
        when(values.get("trending:v1:6")).thenReturn(null);
        when(values.setIfAbsent(eq("trending:lock:v1:6"), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger calls = counter();

        List<TrendingHashtag> result = cache.getOrCompute(6, () -> { calls.incrementAndGet(); return LIST; });

        assertThat(result).isEqualTo(LIST);
        assertThat(calls).hasValue(1);
        verify(values, times(1 + TrendingCache.WAIT_STEPS)).get("trending:v1:6");
        verify(redis, never()).delete("trending:lock:v1:6");
    }

    @Test
    void redisBeingDownMeansComputingDirectlyAndNotAskingAgainDuringTheCooldown() {
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("down"));
        AtomicInteger calls = counter();

        assertThat(cache.getOrCompute(6, () -> { calls.incrementAndGet(); return LIST; })).isEqualTo(LIST);
        assertThat(cache.getOrCompute(6, () -> { calls.incrementAndGet(); return LIST; })).isEqualTo(LIST);
        assertThat(cache.getOrCompute(24, () -> { calls.incrementAndGet(); return LIST; })).isEqualTo(LIST);

        assertThat(calls).hasValue(3);
        verify(values, times(1)).get(anyString()); // one failed attempt, then none for the cooldown
    }

    @Test
    void aFailureWhileStoringStillReturnsTheResult() {
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("down")).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.getOrCompute(6, () -> LIST)).isEqualTo(LIST);
    }

    @Test
    void aDamagedEntryIsDroppedAndRecomputed() {
        when(values.get("trending:v1:6")).thenReturn("{not json");
        when(values.setIfAbsent(eq("trending:lock:v1:6"), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(cache.getOrCompute(6, () -> LIST)).isEqualTo(LIST);

        verify(redis).delete("trending:v1:6");
        verify(values).set("trending:v1:6", JSON, TTL);
    }

    @Test
    void aFailureWhileComputingReachesTheCallerAndFreesTheLock() {
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> cache.getOrCompute(6, () -> { throw new IllegalStateException("database down"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("database down");

        verify(redis).delete("trending:lock:v1:6");
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        // And that was a database problem, not a Redis one: the cache keeps being used.
        assertThat(cache.getOrCompute(6, () -> LIST)).isEqualTo(LIST);
    }

    @Test
    void anEmptyListIsCachedToo() {
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        cache.getOrCompute(6, List::of);

        verify(values).set("trending:v1:6", "[]", TTL);
    }
}
