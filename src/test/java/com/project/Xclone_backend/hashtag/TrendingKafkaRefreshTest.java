package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.project.Xclone_backend.auth.EmailSender;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The whole pipeline: a post made through the API -> event on Kafka -> consumer -> refresher -> the list cached in Redis changes, long before its
 * TTL (set to five minutes here, so only the refresh can explain it). Needs a real Redis on localhost:6379; skipped when there is none.
 */
@SpringBootTest(properties = {"app.kafka.enabled=true", "app.trending.cache-ttl=300s", "app.kafka.refresh-interval=1s", "app.kafka.partitions=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "post-events", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class TrendingKafkaRefreshTest {

    private static final String KEY = TrendingCache.KEY_PREFIX + "6";

    @Autowired MockMvc mvc;
    @Autowired HashtagService service;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockitoBean EmailSender emailSender;
    @MockitoBean S3Client s3Client;
    @MockitoBean S3Presigner s3Presigner;

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void connect() throws Exception {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        try {
            redis.getConnectionFactory().getConnection().ping();
        } catch (RuntimeException e) {
            factory.destroy();
            factory = null;
            assumeTrue(false, "Redis not reachable");
        }
        redis.delete(KEY);
        // Only events from now on are consumed, so be sure the consumer has its partitions before anything is posted.
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @AfterEach
    void disconnect() {
        if (factory != null) {
            redis.delete(KEY);
            factory.destroy();
        }
    }

    private record Account(long id, String token) {
    }

    private Account register() throws Exception {
        String username = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"password\":\"password123\",\"displayName\":\"R\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(body, "$.user.id")).longValue(), JsonPath.read(body, "$.accessToken"));
    }

    private long publish(Account a, String content) throws Exception {
        String res = mvc.perform(post("/api/posts").header("Authorization", "Bearer " + a.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + content + "\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    /** Waits for the cached JSON to satisfy {@code condition}; the cache is never touched by the test itself while waiting. */
    private String awaitCached(java.util.function.Predicate<String> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String cached = redis.opsForValue().get(KEY);
        while (System.nanoTime() < deadline && (cached == null || !condition.test(cached))) {
            Thread.sleep(200);
            cached = redis.opsForValue().get(KEY);
        }
        return cached;
    }

    @Test
    void aNewTagAppearsInTheCachedListWithinSecondsAndTheTtlStartsOver() throws Exception {
        String tag = "kafka" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Account first = register();
        Account second = register();
        service.trending(6, 50); // somebody looks at trending: it is now cached
        String before = redis.opsForValue().get(KEY);
        assertThat(before).isNotNull().doesNotContain(tag);

        publish(first, "breaking #" + tag);
        publish(second, "also #" + tag);   // two different authors: enough to be eligible

        String after = awaitCached(json -> json.contains(tag));

        assertThat(after).as("the cached list was refreshed by the event").contains("\"name\":\"" + tag + "\"");
        assertThat(redis.getExpire(KEY, TimeUnit.SECONDS)).as("and its lifetime restarted").isBetween(250L, 300L);
        assertThat(service.trending(6, 50)).extracting(HashtagDtos.TrendingHashtag::name).contains(tag);
    }

    @Test
    void deletingTheLastPostsOfATagTakesItOutOfTheCachedList() throws Exception {
        String tag = "gone" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Account first = register();
        Account second = register();
        long one = publish(first, "#" + tag + " one");
        publish(second, "#" + tag + " two");
        redis.delete(KEY);
        service.trending(6, 50);
        assertThat(redis.opsForValue().get(KEY)).contains(tag);

        mvc.perform(delete("/api/posts/" + one).header("Authorization", "Bearer " + first.token())).andExpect(status().isNoContent());

        String after = awaitCached(json -> !json.contains(tag));
        assertThat(after).as("only one author is left, so the tag cannot trend any more").doesNotContain(tag);
    }

    @Test
    void anAccountBeingDeactivatedTakesItsPostsOutOfTheCachedList() throws Exception {
        String tag = "hide" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Account first = register();
        Account second = register();
        publish(first, "#" + tag + " one");
        publish(second, "#" + tag + " two");
        redis.delete(KEY);
        service.trending(6, 50);
        assertThat(redis.opsForValue().get(KEY)).contains(tag);

        mvc.perform(post("/api/users/me/deactivate").header("Authorization", "Bearer " + first.token())).andExpect(status().isNoContent());

        assertThat(awaitCached(json -> !json.contains(tag))).doesNotContain(tag);
    }
}
