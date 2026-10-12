package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;

class TrendingRefresherTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final long INTERVAL_NANOS = INTERVAL.toNanos();

    private HashtagService service;
    private TrendingCache cache;
    private ScheduledExecutorService executor;
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    private TrendingRefresher refresher;

    @BeforeEach
    void setUp() {
        service = mock(HashtagService.class);
        cache = mock(TrendingCache.class);
        executor = mock(ScheduledExecutorService.class);
        refresher = new TrendingRefresher(service, cache, INTERVAL, executor, clock::get);
    }

    private Runnable scheduledTask(long expectedDelayNanos) {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(task.capture(), eq(expectedDelayNanos), eq(TimeUnit.NANOSECONDS));
        return task.getValue();
    }

    @Test
    void theFirstRequestIsRefreshedAtOnce() {
        refresher.request();

        scheduledTask(0);
    }

    @Test
    void manyRequestsWhileOneIsWaitingScheduleOnlyOne() {
        for (int i = 0; i < 100; i++) {
            refresher.request();
        }

        verify(executor, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void aRefreshStartsNoSoonerThanTheIntervalAfterTheLastOne() {
        refresher.request();
        Runnable first = scheduledTask(0);
        first.run();                                   // runs at "time zero"
        clock.addAndGet(2_000_000_000L);               // two seconds later another change arrives

        refresher.request();

        verify(executor).schedule(any(Runnable.class), eq(INTERVAL_NANOS - 2_000_000_000L), eq(TimeUnit.NANOSECONDS));
    }

    @Test
    void afterTheIntervalHasPassedARequestIsRefreshedAtOnce() {
        refresher.request();
        scheduledTask(0).run();
        clock.addAndGet(INTERVAL_NANOS + 1);

        refresher.request();

        verify(executor, times(2)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.NANOSECONDS));
    }

    @Test
    void aChangeThatArrivesWhileARefreshIsRunningGetsAnotherRefresh() {
        when(cache.cachedWindows()).thenReturn(List.of(6));
        org.mockito.Mockito.doAnswer(invocation -> {
            refresher.request();                       // a post is created in the middle of the refresh
            return null;
        }).when(cache).refresh(eq(6), any());
        refresher.request();
        Runnable first = scheduledTask(0);

        first.run();

        // The first refresh went out at once; the change made during it gets its own, a full interval after that one started.
        verify(executor, times(1)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.NANOSECONDS));
        verify(executor, times(1)).schedule(any(Runnable.class), eq(INTERVAL_NANOS), eq(TimeUnit.NANOSECONDS));
    }

    @Test
    void onlyTheWindowsThatAreCachedAreRefreshed() {
        when(cache.cachedWindows()).thenReturn(List.of(6, 24));
        refresher.request();

        scheduledTask(0).run();

        verify(cache).refresh(eq(6), any());
        verify(cache).refresh(eq(24), any());
        verify(cache, times(2)).refresh(anyInt(), any());
    }

    @Test
    void theRefreshComputesThroughTheService() {
        when(cache.cachedWindows()).thenReturn(List.of(6));
        List<TrendingHashtag> list = List.of(new TrendingHashtag("java", 3, 2));
        when(service.computeTrending(6)).thenReturn(list);
        refresher.request();
        scheduledTask(0).run();

        ArgumentCaptor<Supplier<List<TrendingHashtag>>> compute = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).refresh(eq(6), compute.capture());
        assertThat(compute.getValue().get()).isEqualTo(list);
    }

    @Test
    void nothingIsRefreshedWhenNothingIsCached() {
        when(cache.cachedWindows()).thenReturn(List.of());
        refresher.request();

        scheduledTask(0).run();

        verify(cache, never()).refresh(anyInt(), any());
        verify(service, never()).computeTrending(anyInt());
    }

    @Test
    void aFailingRefreshIsSwallowedAndTheNextRequestStillWorks() {
        when(cache.cachedWindows()).thenReturn(List.of(6));
        org.mockito.Mockito.doThrow(new IllegalStateException("database down")).when(cache).refresh(eq(6), any());
        refresher.request();
        scheduledTask(0).run(); // must not throw

        clock.addAndGet(INTERVAL_NANOS);
        refresher.request();

        verify(executor, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void aRefresherThatIsShuttingDownDoesNotBreakTheListener() {
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenThrow(new RejectedExecutionException());

        refresher.request(); // must not throw
        refresher.request(); // and is not stuck thinking one is pending

        verify(executor, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }
}
