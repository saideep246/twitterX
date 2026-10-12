package com.project.Xclone_backend.hashtag;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A short-lived Redis copy of the trending list, so the (comparatively expensive) trending query runs about once per TTL per window length instead of
 * on every page view, and every instance shares the result.
 *
 * <p>The cached value is the top {@link HashtagService#TRENDING_MAX_LIMIT} for one window length; a smaller {@code limit} is a prefix of it, so
 * requests for different limits share one entry. On a miss one caller (the holder of a short Redis lock) computes and stores; the others wait a
 * moment for that result instead of all querying the database at once, and compute it themselves only if it does not arrive.
 *
 * <p>Redis is an optimisation, never a dependency: if it is unreachable the list is computed directly, and Redis is not tried again for
 * {@link #COOLDOWN} so an outage does not add a timeout to every request (the same approach as the rate limiter). A TTL of 0 switches the cache off.
 * Results can be up to one TTL old; nothing needs to invalidate them.
 */
@Component
public class TrendingCache {

    static final String KEY_PREFIX = "trending:v1:";
    static final String LOCK_PREFIX = "trending:lock:v1:";
    static final Duration LOCK_TTL = Duration.ofSeconds(15);
    static final Duration COOLDOWN = Duration.ofSeconds(30);
    /** How long a caller that lost the race waits for the winner: {@code WAIT_STEPS * WAIT_STEP}. */
    static final int WAIT_STEPS = 10;
    static final Duration WAIT_STEP = Duration.ofMillis(50);

    private static final Logger log = LoggerFactory.getLogger(TrendingCache.class);
    private static final TypeReference<List<TrendingHashtag>> LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;
    private final AtomicLong skipRedisUntilNanos = new AtomicLong();

    @Autowired
    public TrendingCache(StringRedisTemplate redis, JsonMapper json, @Value("${app.trending.cache-ttl:30s}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    /** A cache that never caches: every call computes. */
    static TrendingCache disabled() {
        return new TrendingCache(null, null, Duration.ZERO);
    }

    boolean enabled() {
        return redis != null && !ttl.isZero() && !ttl.isNegative();
    }

    /** The trending list for {@code hours}, from Redis when fresh, otherwise from {@code compute} (whose result is then stored). */
    public List<TrendingHashtag> getOrCompute(int hours, Supplier<List<TrendingHashtag>> compute) {
        if (!enabled() || System.nanoTime() - skipRedisUntilNanos.get() < 0) {
            return compute.get();
        }
        String key = KEY_PREFIX + hours;
        String lockKey = LOCK_PREFIX + hours;
        boolean locked;
        try {
            List<TrendingHashtag> hit = read(key);
            if (hit != null) {
                return hit;
            }
            locked = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
            if (!locked) {
                hit = waitForWinner(key);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (RuntimeException e) {
            redisDown(e);
            return compute.get();
        }

        List<TrendingHashtag> result;
        try {
            result = compute.get();
        } catch (RuntimeException e) {
            if (locked) {
                release(lockKey);
            }
            throw e;
        }
        store(key, result);
        if (locked) {
            release(lockKey);
        }
        return result;
    }

    /**
     * The window lengths that currently have a cached entry. Empty when the cache is off or Redis cannot be reached.
     * The keys are looked up with SCAN (never KEYS), and there can be at most 168 of them.
     */
    List<Integer> cachedWindows() {
        if (!enabled() || System.nanoTime() - skipRedisUntilNanos.get() < 0) {
            return List.of();
        }
        List<Integer> windows = new java.util.ArrayList<>();
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                try {
                    windows.add(Integer.parseInt(key.substring(KEY_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        } catch (RuntimeException e) {
            redisDown(e);
            return List.of();
        }
        java.util.Collections.sort(windows);
        return windows;
    }

    /** Computes the list for {@code hours} now and stores it, replacing what was there (and restarting its TTL). Does nothing when the cache is off. */
    void refresh(int hours, Supplier<List<TrendingHashtag>> compute) {
        if (!enabled() || System.nanoTime() - skipRedisUntilNanos.get() < 0) {
            return;
        }
        store(KEY_PREFIX + hours, compute.get());
    }

    /** The cached list, or null when there is none or it cannot be read (a damaged entry is dropped). Redis errors propagate. */
    private List<TrendingHashtag> read(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, LIST);
        } catch (RuntimeException e) {
            log.warn("Dropping an unreadable trending cache entry {}: {}", key, e.getMessage());
            redis.delete(key);
            return null;
        }
    }

    private List<TrendingHashtag> waitForWinner(String key) {
        for (int i = 0; i < WAIT_STEPS; i++) {
            try {
                Thread.sleep(WAIT_STEP.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            List<TrendingHashtag> hit = read(key);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private void store(String key, List<TrendingHashtag> value) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            redisDown(e);
        }
    }

    private void release(String lockKey) {
        try {
            redis.delete(lockKey);
        } catch (RuntimeException e) {
            redisDown(e); // the lock expires by itself
        }
    }

    private void redisDown(RuntimeException e) {
        skipRedisUntilNanos.set(System.nanoTime() + COOLDOWN.toNanos());
        log.warn("Trending cache cannot reach Redis; computing directly for {}s: {}", COOLDOWN.toSeconds(), e.getMessage());
    }
}
