package com.project.Xclone_backend.hashtag;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Recomputes the cached trending lists soon after something that can change them happened, instead of waiting for the TTL. Only the window
 * lengths that currently have a cached entry are recomputed (the ones people are actually looking at).
 *
 * <p>Requests are coalesced: however many arrive, at most one refresh runs per {@code interval}, and a request that comes in while one is waiting
 * adds nothing, because that refresh will read the database after the change. A change that lands while a refresh is running schedules one more.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class TrendingRefresher {

    private static final Logger log = LoggerFactory.getLogger(TrendingRefresher.class);

    private final HashtagService service;
    private final TrendingCache cache;
    private final long intervalNanos;
    private final ScheduledExecutorService executor;
    private final LongSupplier nanoClock;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile long lastRunNanos;

    @Autowired
    public TrendingRefresher(HashtagService service, TrendingCache cache, @Value("${app.kafka.refresh-interval:5s}") Duration interval) {
        this(service, cache, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "trending-refresher");
            thread.setDaemon(true);
            return thread;
        }), System::nanoTime);
    }

    TrendingRefresher(HashtagService service, TrendingCache cache, Duration interval, ScheduledExecutorService executor, LongSupplier nanoClock) {
        this.service = service;
        this.cache = cache;
        this.intervalNanos = interval.toNanos();
        this.executor = executor;
        this.nanoClock = nanoClock;
        this.lastRunNanos = nanoClock.getAsLong() - intervalNanos; // the first request does not wait
    }

    /** Something changed: refresh soon (and at most once per interval). Cheap and safe to call from a Kafka listener thread. */
    public void request() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        long wait = Math.max(0, intervalNanos - (nanoClock.getAsLong() - lastRunNanos));
        try {
            executor.schedule(this::run, wait, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) { // shutting down
            scheduled.set(false);
        }
    }

    void run() {
        scheduled.set(false); // a change from now on needs another refresh
        lastRunNanos = nanoClock.getAsLong();
        try {
            List<Integer> windows = cache.cachedWindows();
            for (int hours : windows) {
                cache.refresh(hours, () -> service.computeTrending(hours));
            }
            if (!windows.isEmpty()) {
                log.debug("Refreshed the cached trending lists for windows {}", windows);
            }
        } catch (RuntimeException e) {
            log.warn("Could not refresh the trending cache: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
