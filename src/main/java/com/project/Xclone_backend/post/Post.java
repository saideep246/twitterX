package com.project.Xclone_backend.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import com.project.Xclone_backend.hashtag.Hashtag;
import com.project.Xclone_backend.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "posts", indexes = {
        @Index(name = "idx_posts_author_id", columnList = "author_id, id"),
        @Index(name = "idx_posts_parent_id", columnList = "parent_id, id"),
        @Index(name = "idx_posts_repost_of", columnList = "repost_of_id"),
        @Index(name = "idx_posts_quote_of", columnList = "quote_of_id"),
        @Index(name = "idx_posts_root_id", columnList = "root_id, id"),
        @Index(name = "idx_posts_created_at", columnList = "created_at")},
        uniqueConstraints = @UniqueConstraint(name = "uk_posts_repost", columnNames = {"author_id", "repost_of_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Post {

    public static final int MAX_LENGTH = 280;
    public static final int MAX_MEDIA = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = MAX_LENGTH)
    private String content;

    /** Non-null when this post is a reply. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Post parent;

    /**
     * Top-level post of the conversation this reply belongs to; null for top-level posts and repost rows. Lets the
     * conversation's reply policy be enforced at any depth without walking the parent chain.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "root_id")
    private Post root;

    /** Only meaningful on top-level posts; replies inherit their root's policy. */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'EVERYONE'")
    @Column(nullable = false, length = 20)
    private ReplyPolicy replyPolicy = ReplyPolicy.EVERYONE;

    /**
     * Non-null when this row is a repost: it has no content, media or parent of its own and is rendered as the
     * original. Repost rows are never addressable by id (see {@link PostRepository#findLive}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repost_of_id")
    private Post repostOf;

    /**
     * Non-null when this post quotes another. It is a normal post with its own text; the quoted post is never
     * changed. Posts are only ever soft-deleted, so this reference cannot dangle.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_of_id")
    private Post quoteOf;

    @Column(nullable = false)
    private int likeCount;

    @Column(nullable = false)
    private int replyCount;

    /** The column default lets ddl-auto add this column to tables that already have rows. */
    @ColumnDefault("0")
    @Column(nullable = false)
    private int repostCount;

    /** Soft delete keeps reply threads intact. */
    @Column(nullable = false)
    private boolean deleted;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 50)
    private List<PostMedia> media = new ArrayList<>();

    /** Derived from content; kept in sync by PostService on create and edit. */
    @ManyToMany
    @JoinTable(name = "post_hashtags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "hashtag_id"),
            indexes = {
                    @Index(name = "idx_post_hashtags_hashtag", columnList = "hashtag_id, post_id"),
                    // The primary key leads with hashtag_id, so without this every "tags of this post" lookup (editing a post, deleting an account,
                    // trending) would have to scan the whole link table.
                    @Index(name = "idx_post_hashtags_post", columnList = "post_id, hashtag_id")})
    private Set<Hashtag> hashtags = new HashSet<>();

    /**
     * Active users @mentioned in the content; kept in sync by PostService on create and edit, and cleared on delete.
     * Notifications can later be driven from this relationship.
     */
    @ManyToMany
    @JoinTable(name = "post_mentions",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"),
            indexes = @Index(name = "idx_post_mentions_user", columnList = "user_id, post_id"))
    @BatchSize(size = 50)
    private Set<User> mentions = new HashSet<>();

    public void addMedia(String r2Key) {
        PostMedia m = new PostMedia();
        m.setPost(this);
        m.setR2Key(r2Key);
        m.setPosition(media.size());
        media.add(m);
    }
}
