package com.project.Xclone_backend.hashtag;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds trending test data straight in the database with explicit timestamps, so a test never depends on the real clock. Posts get their
 * hashtags linked directly (the hashtag text in the content is only for readability).
 */
final class TrendingFixture {

    private final JdbcTemplate jdbc;

    TrendingFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An active public account created at {@code createdAt}. */
    long user(Instant createdAt) {
        String name = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.queryForObject("""
                insert into users (username, email, password_hash, display_name, status, protected_account, is_admin, email_verified, created_at)
                values (?, ?, 'x', 'T', 'ACTIVE', false, false, true, ?) returning id
                """, Long.class, name, name + "@example.com", Timestamp.from(createdAt));
    }

    void setStatus(long userId, String status) {
        jdbc.update("update users set status = ? where id = ?", status, userId);
    }

    void setProtected(long userId, boolean value) {
        jdbc.update("update users set protected_account = ? where id = ?", value, userId);
    }

    long tag(String name) {
        jdbc.update("insert into hashtags (name) values (?) on conflict (name) do nothing", name);
        return jdbc.queryForObject("select id from hashtags where name = ?", Long.class, name);
    }

    /** A live post by {@code authorId} at {@code at}, carrying every tag in {@code tags}. */
    long post(long authorId, Instant at, String... tags) {
        long id = jdbc.queryForObject("""
                insert into posts (author_id, content, like_count, reply_count, repost_count, deleted, created_at)
                values (?, ?, 0, 0, 0, false, ?) returning id
                """, Long.class, authorId, "post about " + String.join(" #", tags), Timestamp.from(at));
        for (String tag : tags) {
            jdbc.update("insert into post_hashtags (hashtag_id, post_id) values (?, ?)", tag(tag), id);
        }
        return id;
    }

    void delete(long postId) {
        jdbc.update("update posts set deleted = true where id = ?", postId);
    }

    /** {@code count} brand-new accounts that are old enough to count fully (see {@code app.trending.mature-account-days}). */
    List<Long> matureUsers(int count, Instant now) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(user(now.minusSeconds(90L * 24 * 3600)));
        }
        return ids;
    }
}
