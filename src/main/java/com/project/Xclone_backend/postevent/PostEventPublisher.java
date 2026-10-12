package com.project.Xclone_backend.postevent;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends {@link PostEvent}s to Kafka once the transaction that produced them has committed (an event from a rolled-back change never leaves).
 *
 * <p>Delivery is best effort and can never hurt a request: sending happens on a separate thread behind a bounded queue (a full queue drops the
 * event), a send failure is logged, and after one the broker is not tried again for {@link #COOLDOWN}. That is acceptable because the events only
 * make the trending cache fresher sooner; its TTL still bounds how stale it can get. The message key is the author id, so one account's events
 * stay in order on one partition.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PostEventPublisher {

    static final Duration COOLDOWN = Duration.ofSeconds(30);
    static final int QUEUE_CAPACITY = 1000;

    private static final Logger log = LoggerFactory.getLogger(PostEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String topic;
    private final Executor executor;
    private final AtomicLong skipKafkaUntilNanos = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    @Autowired
    public PostEventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json, @Value("${app.kafka.topic:post-events}") String topic) {
        this(kafka, json, topic, newExecutor());
    }

    PostEventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json, String topic, Executor executor) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
        this.executor = executor;
    }

    private static ThreadPoolExecutor newExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
            Thread thread = new Thread(runnable, "post-event-publisher");
            thread.setDaemon(true);
            return thread;
        });
        return pool;
    }

    /** {@code fallbackExecution}: a change made outside a transaction is sent straight away. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPostEvent(PostEvent event) {
        try {
            executor.execute(() -> send(event));
        } catch (RuntimeException e) { // a full queue
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                log.warn("Post event queue is full; {} events dropped so far (the trending cache will catch up when its TTL ends)", total);
            }
        }
    }

    long dropped() {
        return dropped.get();
    }

    void send(PostEvent event) {
        if (System.nanoTime() - skipKafkaUntilNanos.get() < 0) {
            return;
        }
        try {
            String key = String.valueOf(event.authorId());
            kafka.send(topic, key, json.writeValueAsString(event)).whenComplete((result, error) -> {
                if (error != null) {
                    kafkaDown(error);
                }
            });
        } catch (RuntimeException e) {
            kafkaDown(e);
        }
    }

    private void kafkaDown(Throwable e) {
        skipKafkaUntilNanos.set(System.nanoTime() + COOLDOWN.toNanos());
        log.warn("Cannot send post events to Kafka; skipping them for {}s: {}", COOLDOWN.toSeconds(), e.getMessage());
    }

    @PreDestroy
    void stop() {
        if (executor instanceof ThreadPoolExecutor pool) {
            pool.shutdown();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
