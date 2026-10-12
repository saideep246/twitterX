package com.project.Xclone_backend.postevent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Something happened that can change what is trending: a post carrying hashtags was created, deleted, or had its hashtags changed, or an account
 * changed in a way that adds or removes its posts from public view (deactivated, suspended, removed, reactivated, protected or unprotected).
 *
 * <p>Published as a Spring event inside the transaction that made the change; it only leaves the process after that transaction commits, and
 * only when Kafka is switched on (see {@link PostEventPublisher}). {@code postId} and {@code tags} are empty for {@link Type#ACCOUNT_CHANGED}.
 */
public record PostEvent(Type type, Long postId, Long authorId, List<String> tags, Instant at) {

    public enum Type {
        POST_CREATED, POST_DELETED, POST_TAGS_CHANGED, ACCOUNT_CHANGED
    }

    public static PostEvent created(long postId, long authorId, Collection<String> tags) {
        return new PostEvent(Type.POST_CREATED, postId, authorId, sorted(tags), Instant.now());
    }

    public static PostEvent deleted(long postId, long authorId, Collection<String> tags) {
        return new PostEvent(Type.POST_DELETED, postId, authorId, sorted(tags), Instant.now());
    }

    /** {@code tags}: every tag that was on the post before or after the edit. */
    public static PostEvent tagsChanged(long postId, long authorId, Collection<String> tags) {
        return new PostEvent(Type.POST_TAGS_CHANGED, postId, authorId, sorted(tags), Instant.now());
    }

    public static PostEvent accountChanged(long userId) {
        return new PostEvent(Type.ACCOUNT_CHANGED, null, userId, List.of(), Instant.now());
    }

    private static List<String> sorted(Collection<String> tags) {
        return tags.stream().sorted().toList();
    }
}
