package com.project.Xclone_backend.postevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.project.Xclone_backend.auth.EmailSender;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Post and account changes reach the Kafka topic after they commit, with the right content, and nothing is sent for a change that rolls back. */
@SpringBootTest(properties = "app.kafka.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "post-events", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class PostEventsKafkaTest {

    @Autowired MockMvc mvc;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper json;
    @MockitoBean EmailSender emailSender;
    @MockitoBean S3Client s3Client;
    @MockitoBean S3Presigner s3Presigner;

    private Consumer<String, String> consumer;

    record Account(long id, String username, String token) {
    }

    @BeforeEach
    void listen() {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(broker, "test-" + UUID.randomUUID(), true));
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", StringDeserializer.class);
        consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        // The app creates the topic with several partitions (authors are spread over them), so read all of them.
        List<TopicPartition> partitions = consumer.partitionsFor("post-events").stream()
                .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
    }

    @AfterEach
    void stop() {
        consumer.close();
    }

    private Account register() throws Exception {
        String username = "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"password\":\"password123\",\"displayName\":\"K\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(body, "$.user.id")).longValue(), username, JsonPath.read(body, "$.accessToken"));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, Account a) {
        return b.header("Authorization", "Bearer " + a.token());
    }

    private long publish(Account a, String content) throws Exception {
        String res = mvc.perform(auth(post("/api/posts"), a).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    /**
     * The events on the topic for one account, in order. Events are sent from a background thread, so this waits (up to 15 s) until the
     * {@code expected} number has arrived, then listens another 1.5 s to catch any that should not have been sent.
     */
    private List<JsonNode> eventsOf(Account a, int expected) {
        List<JsonNode> found = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        long quietUntil = Long.MAX_VALUE;
        while (System.nanoTime() < deadline && System.nanoTime() < quietUntil) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                JsonNode event = json.readTree(record.value());
                if (event.get("authorId").asLong() == a.id()) {
                    assertThat(record.key()).as("keyed by the author").isEqualTo(String.valueOf(a.id()));
                    found.add(event);
                }
            }
            if (found.size() >= expected && quietUntil == Long.MAX_VALUE) {
                quietUntil = System.nanoTime() + Duration.ofMillis(1500).toNanos();
            }
        }
        return found;
    }

    private static List<String> tags(JsonNode event) {
        List<String> tags = new ArrayList<>();
        event.get("tags").forEach(t -> tags.add(t.asString()));
        return tags;
    }

    @Test
    void creatingEditingAndDeletingAPostWithTagsPublishesTheMatchingEvents() throws Exception {
        Account a = register();
        long postId = publish(a, "hello #alpha #beta");
        long untagged = publish(a, "no tags here");

        mvc.perform(auth(patch("/api/posts/" + postId), a).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"changed #beta #gamma\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(patch("/api/posts/" + postId), a).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"only the words change #beta #gamma\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(delete("/api/posts/" + postId), a)).andExpect(status().isNoContent());

        List<JsonNode> sent = eventsOf(a, 3);

        assertThat(sent).extracting(e -> e.get("type").asString()).containsExactly("POST_CREATED", "POST_TAGS_CHANGED", "POST_DELETED");
        assertThat(sent).allSatisfy(e -> assertThat(e.get("postId").asLong()).isEqualTo(postId));
        assertThat(sent).extracting(e -> e.get("postId").asLong()).doesNotContain(untagged);
        assertThat(tags(sent.get(0))).containsExactly("alpha", "beta");
        assertThat(tags(sent.get(1))).containsExactly("alpha", "beta", "gamma"); // every tag the post had before or after the edit
        assertThat(tags(sent.get(2))).containsExactly("beta", "gamma");
    }

    @Test
    void accountChangesThatShowOrHideTheirPostsPublishAnAccountEvent() throws Exception {
        Account a = register();

        mvc.perform(auth(patch("/api/users/me"), a).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"New name\"}")).andExpect(status().isOk());
        mvc.perform(auth(patch("/api/users/me"), a).contentType(MediaType.APPLICATION_JSON).content("{\"protectedAccount\":true}")).andExpect(status().isOk());
        mvc.perform(auth(patch("/api/users/me"), a).contentType(MediaType.APPLICATION_JSON).content("{\"protectedAccount\":true}")).andExpect(status().isOk());
        mvc.perform(auth(patch("/api/users/me"), a).contentType(MediaType.APPLICATION_JSON).content("{\"protectedAccount\":false}")).andExpect(status().isOk());
        mvc.perform(auth(post("/api/users/me/deactivate"), a)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + a.username() + "\",\"password\":\"password123\"}")).andExpect(status().isOk());

        List<JsonNode> sent = eventsOf(a, 4);

        // protect, unprotect, deactivate, reactivate: four. Renaming and a repeated "protect" change nothing about visibility.
        assertThat(sent).extracting(e -> e.get("type").asString()).containsExactly("ACCOUNT_CHANGED", "ACCOUNT_CHANGED", "ACCOUNT_CHANGED", "ACCOUNT_CHANGED");
        assertThat(sent).allSatisfy(e -> {
            assertThat(e.get("postId").isNull()).isTrue();
            assertThat(e.get("tags")).isEmpty();
        });
    }

    @Test
    void deletingTheAccountPublishesAnAccountEvent() throws Exception {
        Account a = register();

        mvc.perform(auth(delete("/api/users/me"), a).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isNoContent());

        assertThat(eventsOf(a, 1)).extracting(e -> e.get("type").asString()).containsExactly("ACCOUNT_CHANGED");
    }

    @Test
    void anEventIsOnlySentOnceItsTransactionHasCommitted() throws Exception {
        Account rolledBack = register();
        Account committed = register();

        transactions.executeWithoutResult(tx -> {
            events.publishEvent(PostEvent.accountChanged(rolledBack.id()));
            tx.setRollbackOnly();
        });
        transactions.executeWithoutResult(tx -> events.publishEvent(PostEvent.accountChanged(committed.id())));

        // Wait for the committed one: by then the rolled-back one (published first) would certainly have been sent as well.
        List<JsonNode> sentForRolledBack = new ArrayList<>();
        List<JsonNode> sentForCommitted = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (sentForCommitted.isEmpty() && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1)).records("post-events")) {
                JsonNode event = json.readTree(record.value());
                if (event.get("authorId").asLong() == committed.id()) {
                    sentForCommitted.add(event);
                } else if (event.get("authorId").asLong() == rolledBack.id()) {
                    sentForRolledBack.add(event);
                }
            }
        }

        assertThat(sentForCommitted).hasSize(1);
        assertThat(sentForRolledBack).isEmpty();
    }
}
