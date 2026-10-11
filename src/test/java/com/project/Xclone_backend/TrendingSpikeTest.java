package com.project.Xclone_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.project.Xclone_backend.auth.EmailSender;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Trending ranks a tag by how far its recent use rises above its normal level. Same mocked beans as AdminReportsTest so both share one Spring context. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrendingSpikeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    EmailSender emailSender;

    @MockitoBean
    S3Client s3Client;

    @MockitoBean
    S3Presigner s3Presigner;

    record Account(long id, String username, String token) {
    }

    private Account register() throws Exception {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"password\":\"password123\",\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(body, "$.user.id")).longValue(), username, JsonPath.read(body, "$.accessToken"));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, Account a) {
        return b.header("Authorization", "Bearer " + a.token());
    }

    private long publish(Account author, String content) throws Exception {
        String res = mvc.perform(auth(post("/api/posts"), author).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    /** Moves a post into the past, to make a tag's history. */
    private void age(long postId, int hours) {
        jdbc.update("update posts set created_at = created_at - make_interval(hours => ?) where id = ?", hours, postId);
    }

    private static Long[] crowd(Account[] accounts) {
        return java.util.Arrays.stream(accounts).map(Account::id).toArray(Long[]::new);
    }

    private List<String> trending() throws Exception {
        String body = mvc.perform(get("/api/trending/hashtags").param("limit", "50")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].name");
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void aSuddenSpikeOutranksATagThatIsAlwaysBusy() throws Exception {
        String evergreen = unique("evergreen"), spike = unique("spike");
        Account[] crowd = new Account[8];
        for (int i = 0; i < crowd.length; i++) {
            crowd[i] = register();
        }
        // Established accounts: trending counts accounts younger than a few days for less (see app.trending.mature-account-days).
        for (Account a : crowd) {
            jdbc.update("update users set created_at = now() - interval '90 days' where id = ?", a.id());
        }
        // The evergreen tag: 8 people use it right now, and each of them posts it in every 6-hour stretch of the past week, so 8 people in a
        // window is simply normal for it.
        for (Account a : crowd) {
            publish(a, "#" + evergreen + " now");
        }
        jdbc.update("""
                insert into posts (author_id, content, like_count, reply_count, repost_count, deleted, created_at)
                select u.id, 'old #' || ?, 0, 0, 0, false, now() - make_interval(hours => s * 6 - 3)
                from users u, generate_series(1, 28) s where u.id = any (?)
                """, evergreen, crowd(crowd));
        jdbc.update("""
                insert into post_hashtags (hashtag_id, post_id)
                select (select id from hashtags where name = ?), p.id from posts p where p.content = 'old #' || ?
                """, evergreen, evergreen);
        // The spike: only 4 people, all just now, never seen before.
        for (int i = 0; i < 4; i++) {
            publish(crowd[i], "#" + spike + " breaking");
        }

        List<String> names = trending();

        assertThat(names).contains(evergreen, spike);
        assertThat(names.indexOf(spike)).isLessThan(names.indexOf(evergreen));
    }

    @Test
    void aTagUsedOnlyLongAgoIsNotTrendingAndOneAccountAloneNeverTrends() throws Exception {
        String stale = unique("stale"), lonely = unique("lonely");
        Account a = register();
        Account b = register();
        age(publish(a, "#" + stale + " one"), 30);
        age(publish(b, "#" + stale + " two"), 30);
        publish(a, "#" + lonely + " one");
        publish(a, "#" + lonely + " two");

        assertThat(trending()).doesNotContain(stale, lonely);
    }

    @Test
    void aDeactivatedOrProtectedAuthorDoesNotMakeATagTrend() throws Exception {
        String tag = unique("quiet");
        Account visible = register();
        Account leaver = register();
        Account secret = register();
        publish(visible, "#" + tag + " public");
        publish(leaver, "#" + tag + " leaving");
        publish(secret, "#" + tag + " secret");
        mvc.perform(auth(patch("/api/users/me"), secret).contentType(MediaType.APPLICATION_JSON).content("{\"protectedAccount\":true}")).andExpect(status().isOk());
        mvc.perform(auth(post("/api/users/me/deactivate"), leaver)).andExpect(status().isNoContent());

        assertThat(trending()).doesNotContain(tag); // only one public active author is left

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"usernameOrEmail\":\"" + leaver.username() + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        assertThat(trending()).contains(tag);
    }
}
