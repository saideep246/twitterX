package com.project.Xclone_backend.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.Xclone_backend.bookmark.Bookmark;
import com.project.Xclone_backend.bookmark.BookmarkRepository;
import com.project.Xclone_backend.common.ApiException;
import com.project.Xclone_backend.common.CursorPage;
import com.project.Xclone_backend.follow.FollowRepository;
import com.project.Xclone_backend.hashtag.Hashtag;
import com.project.Xclone_backend.hashtag.HashtagService;
import com.project.Xclone_backend.like.LikeRepository;
import com.project.Xclone_backend.like.PostLike;
import com.project.Xclone_backend.media.MediaService;
import com.project.Xclone_backend.mention.MentionService;
import com.project.Xclone_backend.notification.NotificationService;
import com.project.Xclone_backend.notification.NotificationType;
import com.project.Xclone_backend.postevent.PostEvent;
import com.project.Xclone_backend.post.PostDtos.CreatePostRequest;
import com.project.Xclone_backend.post.PostDtos.CreateThreadRequest;
import com.project.Xclone_backend.post.PostDtos.PostResponse;
import com.project.Xclone_backend.post.PostDtos.UpdatePostRequest;
import com.project.Xclone_backend.report.ModerationNotifier;
import com.project.Xclone_backend.report.PostReportRepository;
import com.project.Xclone_backend.report.ReportStatus;
import com.project.Xclone_backend.report.ReportReason;
import com.project.Xclone_backend.user.User;
import com.project.Xclone_backend.user.UserDtos.UserSummary;
import com.project.Xclone_backend.user.UserMapper;
import com.project.Xclone_backend.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostService {

    private static final int SEARCH_MIN_LENGTH = 2;
    private static final int SEARCH_MAX_LENGTH = 100;

    private final PostRepository postRepository;
    private final LikeRepository likeRepository;
    private final UserService userService;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final MediaService mediaService;
    private final HashtagService hashtagService;
    private final PostReportRepository postReportRepository;
    private final ModerationNotifier moderationNotifier;
    private final BookmarkRepository bookmarkRepository;
    private final MentionService mentionService;
    private final NotificationService notificationService;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public PostResponse create(Long authorId, CreatePostRequest req) {
        String content = req.content() == null ? "" : req.content().strip();
        List<String> mediaKeys = req.mediaKeys() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.mediaKeys()));
        if (content.isEmpty() && mediaKeys.isEmpty()) {
            throw ApiException.badRequest("A post needs text or at least one image");
        }
        mediaKeys.forEach(key -> mediaService.verifyOwnedUpload(authorId, key));

        Post post = new Post();
        User author = userService.requireById(authorId);
        post.setAuthor(author);
        post.setContent(content);
        mediaKeys.forEach(post::addMedia);
        syncHashtags(post);
        syncMentions(post);

        if (req.replyPolicy() != null && (req.replyToId() != null || req.quotedPostId() != null)) {
            throw ApiException.badRequest("Only a top-level post can set who can reply");
        }
        if (req.replyPolicy() != null) {
            post.setReplyPolicy(req.replyPolicy());
        }
        if (req.quotedPostId() != null) {
            if (req.replyToId() != null) {
                throw ApiException.badRequest("A post cannot be both a reply and a quote");
            }
            Post quoted = requireLiveVisible(req.quotedPostId(), authorId);
            if (quoted.getAuthor().isProtectedAccount() && !quoted.getAuthor().getId().equals(authorId)) {
                throw ApiException.forbidden("Posts from protected accounts cannot be quoted");
            }
            post.setQuoteOf(quoted);
        }
        Post parent = null;
        if (req.replyToId() != null) {
            parent = requireLiveVisible(req.replyToId(), authorId);
            userService.requireNotBlocked(authorId, parent.getAuthor().getId());
            Post root = parent.getRoot() != null ? parent.getRoot() : parent;
            requireCanReply(root, authorId);
            post.setParent(parent);
            post.setRoot(root);
            postRepository.addToReplyCount(parent.getId(), 1);
        }
        postRepository.save(post);
        if (!post.getHashtags().isEmpty()) {
            events.publishEvent(PostEvent.created(post.getId(), authorId, tagNames(post)));
        }
        if (parent != null) {
            notificationService.notify(parent.getAuthor(), author, NotificationType.REPLY, post);
        }
        for (User mentioned : post.getMentions()) {
            if (parent == null || !mentioned.getId().equals(parent.getAuthor().getId())) {
                notificationService.notify(mentioned, author, NotificationType.MENTION, post);
            }
        }
        return postMapper.toCreatedResponse(post);
    }

    /** Creates the posts in order in one transaction: the first starts the thread, each later one replies to the previous. */
    @Transactional
    public List<PostResponse> createThread(Long authorId, CreateThreadRequest req) {
        List<PostResponse> created = new ArrayList<>();
        Long previousId = null;
        for (CreatePostRequest item : req.posts()) {
            if (item.replyToId() != null || item.quotedPostId() != null || item.replyPolicy() != null) {
                throw ApiException.badRequest("Thread posts cannot set replyToId, quotedPostId or replyPolicy");
            }
            CreatePostRequest linked = new CreatePostRequest(item.content(), item.mediaKeys(), previousId, null,
                    previousId == null ? req.replyPolicy() : null);
            PostResponse response = create(authorId, linked);
            created.add(response);
            previousId = response.id();
        }
        return created;
    }

    /**
     * The thread containing the post: the conversation's top-level post plus the author's own posts that chain from
     * it through the author's own replies, oldest first. Replies by other people are not part of the thread.
     */
    @Transactional(readOnly = true)
    public List<PostResponse> thread(Long postId, Long viewerId) {
        Post post = requireLive(postId);
        requireVisible(viewerId, post.getAuthor());
        Post root = post.getRoot() != null ? post.getRoot() : post;
        if (root.isDeleted()) {
            throw ApiException.notFound("Post not found");
        }
        Long authorId = root.getAuthor().getId();
        requireVisible(viewerId, root.getAuthor());
        Set<Long> inThread = new HashSet<>(Set.of(root.getId()));
        List<Post> chain = new ArrayList<>(List.of(root));
        for (Post p : postRepository.findAuthorPostsInConversation(root.getId(), authorId)) {
            if (inThread.contains(p.getParent().getId())) {
                inThread.add(p.getId());
                chain.add(p);
            }
        }
        return postMapper.toResponses(chain, viewerId);
    }

    @Transactional
    public PostResponse updateReplyPolicy(Long postId, Long userId, ReplyPolicy policy) {
        Post post = requireLive(postId);
        if (!post.getAuthor().getId().equals(userId)) {
            throw ApiException.forbidden("You can only change who can reply to your own posts");
        }
        if (post.getParent() != null) {
            throw ApiException.badRequest("Only a top-level post can set who can reply");
        }
        post.setReplyPolicy(policy);
        return postMapper.toResponse(post, userId);
    }

    @Transactional(readOnly = true)
    public PostResponse get(Long postId, Long viewerId) {
        Post post = requireLive(postId);
        requireVisible(viewerId, post.getAuthor());
        return postMapper.toResponse(post, viewerId);
    }

    @Transactional
    public PostResponse update(Long postId, Long userId, UpdatePostRequest req) {
        Post post = requireLive(postId);
        if (!post.getAuthor().getId().equals(userId)) {
            throw ApiException.forbidden("You can only edit your own posts");
        }
        Set<Long> previouslyMentioned = post.getMentions().stream().map(User::getId).collect(Collectors.toSet());
        Set<String> tagsBefore = tagNames(post);
        post.setContent(req.content().strip());
        syncHashtags(post);
        syncMentions(post);
        Set<String> tagsAfter = tagNames(post);
        if (!tagsBefore.equals(tagsAfter)) {
            Set<String> touched = new HashSet<>(tagsBefore);
            touched.addAll(tagsAfter);
            events.publishEvent(PostEvent.tagsChanged(post.getId(), userId, touched));
        }
        for (User mentioned : post.getMentions()) {
            if (!previouslyMentioned.contains(mentioned.getId())) {
                notificationService.notify(mentioned, post.getAuthor(), NotificationType.MENTION, post);
            }
        }
        return postMapper.toResponse(post, userId);
    }

    @Transactional
    public void delete(Long postId, Long userId) {
        Post post = requireLive(postId);
        if (!post.getAuthor().getId().equals(userId)) {
            throw ApiException.forbidden("You can only delete your own posts");
        }
        softDelete(post);
    }

    /**
     * Takes a post out of circulation: marked deleted, mentions cleared, its notifications removed, and the parent's reply count
     * lowered. Shared by an author deleting their own post and an admin removing a reported one, so they always do the same things.
     */
    public void softDelete(Post post) {
        post.setDeleted(true);
        post.getMentions().clear();
        notificationService.removeForPost(post.getId());
        if (post.getParent() != null) {
            postRepository.addToReplyCount(post.getParent().getId(), -1);
        }
        if (!post.getHashtags().isEmpty()) {
            events.publishEvent(PostEvent.deleted(post.getId(), post.getAuthor().getId(), tagNames(post)));
        }
    }

    private static Set<String> tagNames(Post post) {
        return post.getHashtags().stream().map(Hashtag::getName).collect(Collectors.toSet());
    }

    /** Records the report and, if the post had no open report, alerts the admins. The reported post is not changed in any way. */
    @Transactional
    public void report(Long postId, Long reporterId, ReportReason reason) {
        Post post = requireLiveVisible(postId, reporterId);
        if (post.getAuthor().getId().equals(reporterId)) {
            throw ApiException.badRequest("You cannot report your own post");
        }
        boolean alreadyUnderReview = postReportRepository.existsByPostIdAndStatus(postId, ReportStatus.OPEN);
        if (postReportRepository.report(reporterId, postId, reason.name()) == 0) {
            throw ApiException.conflict("You have already reported this post");
        }
        if (!alreadyUnderReview) {
            moderationNotifier.reportReceived("a post by @" + post.getAuthor().getUsername(), reason, post);
        }
    }

    @Transactional
    public void repost(Long postId, Long userId) {
        Post post = requireLiveVisible(postId, userId);
        if (post.getAuthor().getId().equals(userId)) {
            throw ApiException.badRequest("You cannot repost your own post");
        }
        if (post.getAuthor().isProtectedAccount()) {
            throw ApiException.forbidden("Posts from protected accounts cannot be reposted");
        }
        if (postRepository.repost(userId, postId) > 0) {
            postRepository.addToRepostCount(postId, 1);
            notificationService.notify(post.getAuthor(), userService.requireById(userId), NotificationType.REPOST, post);
        }
    }

    @Transactional
    public void unrepost(Long postId, Long userId) {
        requireLive(postId);
        if (postRepository.unrepost(userId, postId) > 0) {
            postRepository.addToRepostCount(postId, -1);
            notificationService.removeRepost(userId, postId);
        }
    }

    @Transactional
    public void like(Long postId, Long userId) {
        Post post = requireLiveVisible(postId, userId);
        userService.requireNotBlocked(userId, post.getAuthor().getId());
        if (likeRepository.like(userId, postId) > 0) {
            postRepository.addToLikeCount(postId, 1);
            notificationService.notify(post.getAuthor(), userService.requireById(userId), NotificationType.LIKE, post);
        }
    }

    @Transactional
    public void unlike(Long postId, Long userId) {
        requireLive(postId);
        if (likeRepository.unlike(userId, postId) > 0) {
            postRepository.addToLikeCount(postId, -1);
            notificationService.removeLike(userId, postId);
        }
    }

    @Transactional
    public void bookmark(Long postId, Long userId) {
        requireLiveVisible(postId, userId);
        if (bookmarkRepository.bookmark(userId, postId) == 0) {
            throw ApiException.conflict("You have already bookmarked this post");
        }
    }

    @Transactional
    public void unbookmark(Long postId, Long userId) {
        requireLive(postId);
        bookmarkRepository.unbookmark(userId, postId);
    }

    /** Cursor here is the bookmark id, so paging follows "most recently bookmarked" order. */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> bookmarks(Long userId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        List<Bookmark> rows = bookmarkRepository.findUserBookmarks(userId, CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Bookmark::getId,
                page -> postMapper.toResponses(page.stream().map(Bookmark::getPost).toList(), userId));
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> replies(Long postId, Long viewerId, Long cursor, Integer limit) {
        requireLiveVisible(postId, viewerId);
        int n = CursorPage.clampLimit(limit);
        List<Post> rows = postRepository.findReplies(postId, viewerId, cursor == null ? 0L : cursor,
                Limit.of(n + 1));
        return CursorPage.of(rows, n, Post::getId, page -> postMapper.toResponses(page, viewerId));
    }

    /** The people who reposted a post (plain reposts, the ones counted in {@code repostCount}), newest first. */
    @Transactional(readOnly = true)
    public CursorPage<UserSummary> reposters(Long postId, Long viewerId, Long cursor, Integer limit) {
        requireLiveVisible(postId, viewerId);
        int n = CursorPage.clampLimit(limit);
        List<Post> rows = postRepository.findReposts(postId, viewerId, CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Post::getId, page -> page.stream().map(p -> userMapper.toSummary(p.getAuthor())).toList());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> likers(Long postId, Long viewerId, Long cursor, Integer limit) {
        requireLiveVisible(postId, viewerId);
        int n = CursorPage.clampLimit(limit);
        List<PostLike> rows = likeRepository.findLikers(postId, CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, PostLike::getId,
                page -> page.stream().map(l -> userMapper.toSummary(l.getUser())).toList());
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> userPosts(String username, Long viewerId, Long cursor, Integer limit) {
        User user = userService.requireByUsername(username);
        requireVisible(viewerId, user);
        int n = CursorPage.clampLimit(limit);
        List<Post> rows = postRepository.findUserPosts(user.getId(), viewerId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        return CursorPage.of(rows, n, Post::getId, page -> postMapper.toResponses(page, viewerId));
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> userReplies(String username, Long viewerId, Long cursor, Integer limit) {
        User user = userService.requireByUsername(username);
        requireVisible(viewerId, user);
        int n = CursorPage.clampLimit(limit);
        List<Post> rows = postRepository.findUserReplies(user.getId(), CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Post::getId, page -> postMapper.toResponses(page, viewerId));
    }

    /** Cursor here is the like id, so paging follows "most recently liked" order. */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> userLikes(String username, Long viewerId, Long cursor, Integer limit) {
        User user = userService.requireByUsername(username);
        requireVisible(viewerId, user);
        int n = CursorPage.clampLimit(limit);
        List<PostLike> rows = likeRepository.findUserLikes(user.getId(), viewerId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        return CursorPage.of(rows, n, PostLike::getId,
                page -> postMapper.toResponses(page.stream().map(PostLike::getPost).toList(), viewerId));
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> hashtagPosts(String name, Long viewerId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        List<Post> rows = postRepository.findByHashtag(HashtagService.normalize(name), viewerId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        return CursorPage.of(rows, n, Post::getId, page -> postMapper.toResponses(page, viewerId));
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> search(String q, Long viewerId, Long cursor, Integer limit) {
        String term = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (term.length() < SEARCH_MIN_LENGTH) {
            throw ApiException.badRequest("Search query must be at least " + SEARCH_MIN_LENGTH + " characters");
        }
        if (term.length() > SEARCH_MAX_LENGTH) {
            throw ApiException.badRequest("Search query must be at most " + SEARCH_MAX_LENGTH + " characters");
        }
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int n = CursorPage.clampLimit(limit);
        List<Post> rows = postRepository.searchByContent("%" + escaped + "%", viewerId,
                CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Post::getId, page -> postMapper.toResponses(page, viewerId));
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> timeline(Long userId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        List<Object[]> rows = postRepository.findTimelineWithViewerFlags(userId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        List<Post> posts = new ArrayList<>(rows.size());
        Set<Long> liked = new HashSet<>();
        Set<Long> reposted = new HashSet<>();
        Set<Long> bookmarked = new HashSet<>();
        for (Object[] row : rows) {
            Post post = (Post) row[0];
            Long shownId = post.getRepostOf() != null ? post.getRepostOf().getId() : post.getId();
            if (Boolean.TRUE.equals(row[1])) {
                liked.add(shownId);
            }
            if (Boolean.TRUE.equals(row[2])) {
                reposted.add(shownId);
            }
            if (Boolean.TRUE.equals(row[3])) {
                bookmarked.add(shownId);
            }
            posts.add(post);
        }
        PostMapper.ViewerFlags flags = new PostMapper.ViewerFlags(liked, reposted, bookmarked);
        return CursorPage.of(posts, n, Post::getId, page -> postMapper.toResponses(page, userId, flags));
    }

    // --- For You ---
    // How the "For You" feed is ranked (see PostRepository.findForYouIds for the formula). Tune these in one place.

    /** Only posts newer than this are candidates; older ones would need to be very popular to matter anyway. */
    static final Duration FOR_YOU_WINDOW = Duration.ofDays(7);
    /** The latest this many eligible posts in the window are scored. Bounds the cost of one request. */
    static final int FOR_YOU_CANDIDATES = 2000;
    /** At most this many posts by one author, so a single account cannot take over the feed. */
    static final int FOR_YOU_PER_AUTHOR = 3;
    /** The feed ends after this many posts (then nextCursor is null), like the end of a "top posts" list. */
    static final int FOR_YOU_MAX_DEPTH = 200;
    /** A feed is scored at one frozen minute; a cursor older than this starts a fresh feed instead. */
    private static final long FOR_YOU_SNAPSHOT_MAX_AGE_MINUTES = 60;

    /**
     * The ranked "For You" feed. Ranking depends on the clock, so an id cursor cannot page it: the cursor packs the minute the feed was
     * scored at and how many posts were already served ({@code snapshotMinute * 1000 + offset}), and every page is scored at that same minute.
     * A garbage, future or stale cursor just starts a new feed.
     */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> forYou(Long userId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        long nowMinute = Instant.now().getEpochSecond() / 60;
        long snapshot = nowMinute;
        int offset = 0;
        if (cursor != null && cursor > 0) {
            long cursorSnapshot = cursor / 1000;
            long cursorOffset = cursor % 1000;
            if (cursorSnapshot <= nowMinute && nowMinute - cursorSnapshot <= FOR_YOU_SNAPSHOT_MAX_AGE_MINUTES) {
                snapshot = cursorSnapshot;
                offset = (int) cursorOffset;
            }
        }
        int pageSize = Math.min(n, FOR_YOU_MAX_DEPTH - offset);
        if (pageSize <= 0) {
            return new CursorPage<>(List.of(), null);
        }
        Instant scoredAt = Instant.ofEpochSecond((snapshot + 1) * 60); // the end of that minute, so posts made this minute are in
        List<Long> ids = postRepository.findForYouIds(userId, scoredAt, scoredAt.minus(FOR_YOU_WINDOW), FOR_YOU_CANDIDATES,
                FOR_YOU_PER_AUTHOR, offset, pageSize + 1);
        boolean more = ids.size() > pageSize && offset + pageSize < FOR_YOU_MAX_DEPTH;
        List<Long> pageIds = ids.size() > pageSize ? ids.subList(0, pageSize) : ids;
        Map<Long, Post> byId = new HashMap<>();
        postRepository.findAllWithAuthor(pageIds).forEach(p -> byId.put(p.getId(), p));
        List<Post> posts = pageIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        Long next = more ? snapshot * 1000 + offset + pageSize : null;
        return new CursorPage<>(postMapper.toResponses(posts, userId), next);
    }

    /** The author of the conversation can always reply; everyone else must satisfy the root post's policy. */
    private void requireCanReply(Post root, Long replierId) {
        Long rootAuthorId = root.getAuthor().getId();
        if (rootAuthorId.equals(replierId)) {
            return;
        }
        boolean allowed = switch (root.getReplyPolicy()) {
            case EVERYONE -> true;
            case FOLLOWING -> followRepository.existsByFollowerIdAndFolloweeId(rootAuthorId, replierId);
            case MENTIONED -> root.getMentions().stream().anyMatch(u -> u.getId().equals(replierId));
        };
        if (!allowed) {
            throw ApiException.forbidden("The author limits who can reply to this conversation");
        }
    }

    private void requireVisible(Long viewerId, User user) {
        if (viewerId != null) {
            userService.requireNotBlocked(viewerId, user.getId());
        }
        userService.requireCanViewPosts(viewerId, user);
    }

    /** A live post the viewer is allowed to see: posts of protected accounts are visible to the owner and approved followers only. */
    private Post requireLiveVisible(Long postId, Long viewerId) {
        Post post = requireLive(postId);
        userService.requireCanViewPosts(viewerId, post.getAuthor());
        return post;
    }
  
    /** Retain-then-add so Hibernate only writes the link rows that actually changed. */
    private void syncHashtags(Post post) {
        Set<Hashtag> tags = hashtagService.resolve(post.getContent());
        post.getHashtags().retainAll(tags);
        post.getHashtags().addAll(tags);
    }

    private void syncMentions(Post post) {
        Set<User> users = mentionService.resolve(post.getContent());
        post.getMentions().retainAll(users);
        post.getMentions().addAll(users);
    }

    private Post requireLive(Long postId) {
        return postRepository.findLive(postId).orElseThrow(() -> ApiException.notFound("Post not found"));
    }
}
