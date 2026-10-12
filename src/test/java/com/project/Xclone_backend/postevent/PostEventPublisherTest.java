package com.project.Xclone_backend.postevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PostEventPublisherTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private KafkaTemplate<String, String> kafka;
    private final Executor inline = Runnable::run;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void anEventIsSentToTheTopicKeyedByItsAuthorAsJson() throws Exception {
        PostEventPublisher publisher = new PostEventPublisher(kafka, json, "post-events", inline);

        publisher.onPostEvent(new PostEvent(PostEvent.Type.POST_CREATED, 42L, 7L, List.of("java", "spring"), Instant.parse("2026-10-12T10:00:00Z")));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("post-events"), eq("7"), value.capture());
        JsonNode sent = json.readTree(value.getValue());
        assertThat(sent.get("type").asString()).isEqualTo("POST_CREATED");
        assertThat(sent.get("postId").asLong()).isEqualTo(42);
        assertThat(sent.get("authorId").asLong()).isEqualTo(7);
        assertThat(sent.get("tags")).extracting(JsonNode::asString).containsExactly("java", "spring");
        assertThat(sent.get("at").asString()).startsWith("2026-10-12T10:00:00");
    }

    @Test
    void anAccountEventCarriesNoPostAndNoTags() throws Exception {
        PostEventPublisher publisher = new PostEventPublisher(kafka, json, "post-events", inline);

        publisher.onPostEvent(PostEvent.accountChanged(9));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("post-events"), eq("9"), value.capture());
        JsonNode sent = json.readTree(value.getValue());
        assertThat(sent.get("type").asString()).isEqualTo("ACCOUNT_CHANGED");
        assertThat(sent.get("postId").isNull()).isTrue();
        assertThat(sent.get("tags")).isEmpty();
    }

    @Test
    void theTagsOfAnEventAreSortedSoTheMessageDoesNotDependOnSetOrder() {
        assertThat(PostEvent.created(1, 2, List.of("zeta", "alpha", "mid")).tags()).containsExactly("alpha", "mid", "zeta");
    }

    @Test
    void aSendThatThrowsNeverReachesTheCallerAndSilencesKafkaForAWhile() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker down"));
        PostEventPublisher publisher = new PostEventPublisher(kafka, json, "post-events", inline);

        publisher.onPostEvent(PostEvent.accountChanged(1));
        publisher.onPostEvent(PostEvent.accountChanged(2));
        publisher.onPostEvent(PostEvent.accountChanged(3));

        verify(kafka, times(1)).send(anyString(), anyString(), anyString()); // one failed try, then the cooldown
    }

    @Test
    void aSendThatFailsLaterAlsoStartsTheCooldown() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("timed out")));
        PostEventPublisher publisher = new PostEventPublisher(kafka, json, "post-events", inline);

        publisher.onPostEvent(PostEvent.accountChanged(1));
        publisher.onPostEvent(PostEvent.accountChanged(2));

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void aFullQueueDropsEventsInsteadOfBlockingTheRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(null);
        });
        ThreadPoolExecutor small = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        PostEventPublisher publisher = new PostEventPublisher(kafka, json, "post-events", small);
        try {
            publisher.onPostEvent(PostEvent.accountChanged(1));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // the worker is now stuck in a slow send
            long begin = System.nanoTime();
            for (int i = 2; i <= 10; i++) {
                publisher.onPostEvent(PostEvent.accountChanged(i)); // 2 fit in the queue, the other 7 are dropped
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).as("publishing never waits").isLessThan(500);
            assertThat(publisher.dropped()).isEqualTo(7);
        } finally {
            release.countDown();
            small.shutdownNow();
        }
    }

    @Test
    void nothingIsSentForAnEventThatHasNotBeenPublished() {
        new PostEventPublisher(kafka, json, "post-events", inline);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
