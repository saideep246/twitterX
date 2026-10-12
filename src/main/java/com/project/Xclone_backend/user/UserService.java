package com.project.Xclone_backend.user;

import java.util.List;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.Xclone_backend.auth.EmailTokenRepository;
import com.project.Xclone_backend.auth.EmailTokenService;
import com.project.Xclone_backend.auth.RefreshTokenRepository;
import com.project.Xclone_backend.block.Block;
import com.project.Xclone_backend.block.BlockRepository;
import com.project.Xclone_backend.common.ApiException;
import com.project.Xclone_backend.common.CursorPage;
import com.project.Xclone_backend.follow.Follow;
import com.project.Xclone_backend.follow.FollowRepository;
import com.project.Xclone_backend.follow.FollowRequest;
import com.project.Xclone_backend.follow.FollowRequestRepository;
import com.project.Xclone_backend.bookmark.BookmarkRepository;
import com.project.Xclone_backend.like.LikeRepository;
import com.project.Xclone_backend.media.MediaService;
import com.project.Xclone_backend.mute.Mute;
import com.project.Xclone_backend.mute.MuteRepository;
import com.project.Xclone_backend.notification.NotificationService;
import com.project.Xclone_backend.postevent.PostEvent;
import com.project.Xclone_backend.security.ActiveUserCache;
import com.project.Xclone_backend.notification.NotificationType;
import com.project.Xclone_backend.post.PostRepository;
import com.project.Xclone_backend.report.ReportReason;
import com.project.Xclone_backend.report.ModerationNotifier;
import com.project.Xclone_backend.report.ReportStatus;
import com.project.Xclone_backend.report.UserReportRepository;
import com.project.Xclone_backend.user.UserDtos.ChangeEmailRequest;
import com.project.Xclone_backend.user.UserDtos.ChangePasswordRequest;
import com.project.Xclone_backend.user.UserDtos.ChangeUsernameRequest;
import com.project.Xclone_backend.user.UserDtos.DeleteAccountRequest;
import com.project.Xclone_backend.user.UserDtos.ProfileResponse;
import com.project.Xclone_backend.user.UserDtos.UpdateProfileRequest;
import com.project.Xclone_backend.user.UserDtos.UserResponse;
import com.project.Xclone_backend.user.UserDtos.UserSummary;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final int SEARCH_LIMIT = 20;

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final MuteRepository muteRepository;
    private final FollowRequestRepository followRequestRepository;
    private final UserMapper userMapper;
    private final MediaService mediaService;
    private final UserReportRepository userReportRepository;
    private final ModerationNotifier moderationNotifier;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PostRepository postRepository;
    private final LikeRepository likeRepository;
    private final BookmarkRepository bookmarkRepository;
    private final NotificationService notificationService;
    private final EmailTokenService emailTokenService;
    private final EmailTokenRepository emailTokenRepository;
    private final ActiveUserCache activeUserCache;
    private final ApplicationEventPublisher events;

    /** Only active accounts can be looked up by handle: a deactivated, suspended or removed one answers 404 to every action (follow, block, report, message ...). */
    public User requireByUsername(String username) {
        return userRepository.findByUsername(username.toLowerCase(Locale.ROOT))
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public User requireById(Long id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    /** Throws if either user has blocked the other. */
    public void requireNotBlocked(Long userId, Long otherId) {
        if (blockRepository.existsBetween(userId, otherId)) {
            throw ApiException.forbidden("This action is not allowed because of a block");
        }
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return userMapper.toResponse(requireById(userId));
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = requireById(userId);
        if (req.displayName() != null) {
            String name = req.displayName().strip();
            if (name.isEmpty()) {
                throw ApiException.badRequest("Display name cannot be blank");
            }
            user.setDisplayName(name);
        }
        if (req.bio() != null) {
            user.setBio(req.bio().isBlank() ? null : req.bio().strip());
        }
        if (req.avatarKey() != null) {
            user.setAvatarKey(resolveMediaKey(userId, req.avatarKey()));
        }
        if (req.bannerKey() != null) {
            user.setBannerKey(resolveMediaKey(userId, req.bannerKey()));
        }
        if (req.protectedAccount() != null) {
            boolean wasProtected = user.isProtectedAccount();
            user.setProtectedAccount(req.protectedAccount());
            if (wasProtected != req.protectedAccount()) {
                events.publishEvent(PostEvent.accountChanged(userId)); // their posts enter or leave public view
            }
            if (wasProtected && !req.protectedAccount()) {
                // Nobody needs approval any more, so everyone waiting becomes a follower.
                followRepository.approveAllRequestsTo(userId);
                followRequestRepository.deleteAllTo(userId);
                notificationService.removeAllFollowRequestsTo(userId);
            }
        }
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse changeUsername(Long userId, ChangeUsernameRequest req) {
        User user = requireById(userId);
        String username = req.username().toLowerCase(Locale.ROOT);
        if (!username.equals(user.getUsername())) {
            if (userRepository.existsByUsername(username)) {
                throw ApiException.conflict("Username is already taken");
            }
            user.setUsername(username);
        }
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse changeEmail(Long userId, ChangeEmailRequest req) {
        User user = requireById(userId);
        String email = req.email().toLowerCase(Locale.ROOT);
        if (!email.equals(user.getEmail())) {
            if (userRepository.existsByEmail(email)) {
                throw ApiException.conflict("Email is already registered");
            }
            user.setEmail(email);
            user.setEmailVerified(false);
            emailTokenService.sendVerification(user);
        }
        return userMapper.toResponse(user);
    }

    @Transactional
    public void resendEmailVerification(Long userId) {
        User user = requireById(userId);
        if (user.isEmailVerified()) {
            throw ApiException.conflict("Email is already verified");
        }
        emailTokenService.sendVerification(user);
    }

    /** Also signs out every session: refresh tokens are revoked, so each device must log in again. */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User user = requireById(userId);
        requirePassword(user, req.currentPassword());
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        refreshTokenRepository.revokeAllForUser(userId);
    }

    /** Nothing is deleted and all sessions end; logging in with the correct password reactivates the account. */
    @Transactional
    public void deactivate(Long userId) {
        User user = requireById(userId);
        user.setStatus(AccountStatus.DEACTIVATED);
        refreshTokenRepository.revokeAllForUser(userId);
        activeUserCache.evictAfterCommit(userId);
        events.publishEvent(PostEvent.accountChanged(userId));
    }

    /**
     * Removes the user's personal data, likes, follows, sessions and post content. The row itself stays as an
     * anonymized placeholder because other users' replies and reports reference it and its posts.
     */
    @Transactional
    public void deleteAccount(Long userId, DeleteAccountRequest req) {
        User user = requireById(userId);
        requirePassword(user, req.password());
        anonymize(user);
    }

    /** Admin-initiated removal: the same irreversible anonymization as {@link #deleteAccount}, without the password. */
    @Transactional
    public void removeAccountByAdmin(Long userId) {
        anonymize(requireById(userId));
    }

    /** Admin suspension: no sign-in, hidden from others, all sessions end; reversible with {@link #unsuspend}. */
    @Transactional
    public void suspend(Long userId) {
        User user = requireById(userId);
        user.setStatus(AccountStatus.SUSPENDED);
        refreshTokenRepository.revokeAllForUser(userId);
        activeUserCache.evictAfterCommit(userId);
        events.publishEvent(PostEvent.accountChanged(userId));
    }

    @Transactional
    public void unsuspend(Long userId) {
        User user = requireById(userId);
        if (user.getStatus() == AccountStatus.SUSPENDED) {
            user.setStatus(AccountStatus.ACTIVE);
        }
        activeUserCache.evictAfterCommit(userId);
        events.publishEvent(PostEvent.accountChanged(userId));
    }

    private void anonymize(User user) {
        Long userId = user.getId();
        postRepository.decrementReplyCountsForAuthor(userId);
        postRepository.decrementLikeCountsForLiker(userId);
        postRepository.decrementRepostCountsForReposter(userId);
        likeRepository.deleteAllByUser(userId);
        bookmarkRepository.deleteAllByUser(userId);
        followRepository.deleteAllInvolving(userId);
        muteRepository.deleteAllInvolving(userId);
        followRequestRepository.deleteAllInvolving(userId);
        notificationService.removeAllInvolving(userId);
        refreshTokenRepository.deleteAllForUser(userId);
        emailTokenRepository.deleteAllForUser(userId);
        postRepository.deleteHashtagLinksForAuthor(userId);
        postRepository.deleteMentionLinksInvolving(userId);
        postRepository.deleteMediaForAuthor(userId);
        postRepository.softDeleteAndClearAllByAuthor(userId);

        // '~' is not allowed in usernames, so these can never collide with a real account.
        user.setUsername("~" + userId);
        user.setEmail("~" + userId + "@deleted.invalid");
        user.setPasswordHash("!");
        user.setDisplayName("Deleted user");
        user.setBio(null);
        user.setAvatarKey(null);
        user.setBannerKey(null);
        user.setEmailVerified(false);
        user.setStatus(AccountStatus.DELETED);
        activeUserCache.evictAfterCommit(userId);
        events.publishEvent(PostEvent.accountChanged(userId));
    }

    private void requirePassword(User user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
    }

    private String resolveMediaKey(Long userId, String key) {
        if (key.isEmpty()) {
            return null;
        }
        mediaService.verifyOwnedUpload(userId, key);
        return key;
    }

    @Transactional(readOnly = true)
    public ProfileResponse profile(String username, Long viewerId) {
        User found = userRepository.findByUsername(username.toLowerCase(Locale.ROOT))
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE || u.getStatus() == AccountStatus.DEACTIVATED)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        if (found.getStatus() == AccountStatus.DEACTIVATED) {
            return userMapper.toUnavailableProfile(found.getId()); // a blank "XClone user": nothing of the account is shown
        }
        User user = found;
        boolean followedByMe = viewerId != null
                && followRepository.existsByFollowerIdAndFolloweeId(viewerId, user.getId());
        boolean blockedByMe = viewerId != null
                && blockRepository.existsByBlockerIdAndBlockedId(viewerId, user.getId());
        boolean mutedByMe = viewerId != null
                && muteRepository.existsByMuterIdAndMutedId(viewerId, user.getId());
        boolean followRequestedByMe = viewerId != null && user.isProtectedAccount()
                && followRequestRepository.existsByRequesterIdAndTargetId(viewerId, user.getId());
        return userMapper.toProfile(user,
                followRepository.countByFolloweeId(user.getId()),
                followRepository.countByFollowerId(user.getId()),
                followedByMe, blockedByMe, mutedByMe, followRequestedByMe);
    }

    public enum FollowResult { FOLLOWING, REQUESTED }

    /** Following a protected account creates a pending request for its owner to approve instead of a follow. */
    @Transactional
    public FollowResult follow(Long followerId, String username) {
        User target = requireByUsername(username);
        if (target.getId().equals(followerId)) {
            throw ApiException.badRequest("You cannot follow yourself");
        }
        requireNotBlocked(followerId, target.getId());
        if (target.isProtectedAccount()
                && !followRepository.existsByFollowerIdAndFolloweeId(followerId, target.getId())) {
            if (followRequestRepository.request(followerId, target.getId()) > 0) {
                notificationService.notify(target, requireById(followerId), NotificationType.FOLLOW_REQUEST, null);
            }
            return FollowResult.REQUESTED;
        }
        if (followRepository.follow(followerId, target.getId()) > 0) {
            notificationService.notify(target, requireById(followerId), NotificationType.FOLLOW, null);
        }
        return FollowResult.FOLLOWING;
    }

    /** Records the report and, if the account had no open report, alerts the admins. The reported user is not changed in any way. */
    @Transactional
    public void report(Long reporterId, String username, ReportReason reason) {
        User target = requireByUsername(username);
        if (target.getId().equals(reporterId)) {
            throw ApiException.badRequest("You cannot report yourself");
        }
        boolean alreadyUnderReview = userReportRepository.existsByReportedUserIdAndStatus(target.getId(), ReportStatus.OPEN);
        if (userReportRepository.report(reporterId, target.getId(), reason.name()) == 0) {
            throw ApiException.conflict("You have already reported this user");
        }
        if (!alreadyUnderReview) {
            moderationNotifier.reportReceived("account @" + target.getUsername(), reason, null);
        }
    }

    /** Unfollows, or withdraws a pending follow request. */
    @Transactional
    public void unfollow(Long followerId, String username) {
        User target = requireByUsername(username);
        followRepository.unfollow(followerId, target.getId());
        notificationService.removeFollow(followerId, target.getId());
        if (followRequestRepository.cancel(followerId, target.getId()) > 0) {
            notificationService.removeFollowRequest(followerId, target.getId());
        }
    }

    @Transactional
    public void approveFollowRequest(Long ownerId, String requesterUsername) {
        User requester = requireByUsername(requesterUsername);
        if (followRequestRepository.cancel(requester.getId(), ownerId) == 0) {
            throw ApiException.notFound("No pending follow request from this user");
        }
        notificationService.removeFollowRequest(requester.getId(), ownerId);
        if (followRepository.follow(requester.getId(), ownerId) > 0) {
            notificationService.notify(requireById(ownerId), requester, NotificationType.FOLLOW, null);
        }
    }

    @Transactional
    public void denyFollowRequest(Long ownerId, String requesterUsername) {
        User requester = requireByUsername(requesterUsername);
        if (followRequestRepository.cancel(requester.getId(), ownerId) == 0) {
            throw ApiException.notFound("No pending follow request from this user");
        }
        notificationService.removeFollowRequest(requester.getId(), ownerId);
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> followRequests(Long ownerId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        List<FollowRequest> rows = followRequestRepository.findIncoming(ownerId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        return CursorPage.of(rows, n, FollowRequest::getId,
                page -> page.stream().map(r -> userMapper.toSummary(r.getRequester())).toList());
    }

    /** The posts and follower lists of a protected account are visible to its owner and approved followers only. */
    public boolean canViewPosts(Long viewerId, User owner) {
        if (owner.getStatus() != AccountStatus.ACTIVE) {
            return false; // deactivated, suspended or removed: their posts are not shown to anyone
        }
        if (!owner.isProtectedAccount()) {
            return true;
        }
        return viewerId != null && (viewerId.equals(owner.getId())
                || followRepository.existsByFollowerIdAndFolloweeId(viewerId, owner.getId()));
    }

    public void requireCanViewPosts(Long viewerId, User owner) {
        if (owner.getStatus() != AccountStatus.ACTIVE) {
            throw ApiException.notFound("User not found");
        }
        if (!canViewPosts(viewerId, owner)) {
            throw ApiException.forbidden("This account is protected");
        }
    }

    /** Blocking also removes any follow between the two users, in both directions. */
    @Transactional
    public void block(Long blockerId, String username) {
        User target = requireByUsername(username);
        if (target.getId().equals(blockerId)) {
            throw ApiException.badRequest("You cannot block yourself");
        }
        blockRepository.block(blockerId, target.getId());
        followRepository.unfollow(blockerId, target.getId());
        followRepository.unfollow(target.getId(), blockerId);
        notificationService.removeFollow(blockerId, target.getId());
        notificationService.removeFollow(target.getId(), blockerId);
        followRequestRepository.cancel(blockerId, target.getId());
        followRequestRepository.cancel(target.getId(), blockerId);
        notificationService.removeFollowRequest(blockerId, target.getId());
        notificationService.removeFollowRequest(target.getId(), blockerId);
    }

    @Transactional
    public void unblock(Long blockerId, String username) {
        User target = requireByUsername(username);
        blockRepository.unblock(blockerId, target.getId());
    }

    /** Silent and one-way: the muted user is not told, and the follow (if any) stays. */
    @Transactional
    public void mute(Long muterId, String username) {
        User target = requireByUsername(username);
        if (target.getId().equals(muterId)) {
            throw ApiException.badRequest("You cannot mute yourself");
        }
        muteRepository.mute(muterId, target.getId());
    }

    @Transactional
    public void unmute(Long muterId, String username) {
        User target = requireByUsername(username);
        muteRepository.unmute(muterId, target.getId());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> muted(Long userId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        List<Mute> rows = muteRepository.findMuted(userId, CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Mute::getId,
                page -> page.stream().map(m -> userMapper.toSummary(m.getMuted())).toList());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> blocked(Long userId, Long cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        List<Block> rows = blockRepository.findBlocked(userId, CursorPage.cursorOrMax(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Block::getId,
                page -> page.stream().map(b -> userMapper.toSummary(b.getBlocked())).toList());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> followers(String username, Long viewerId, Long cursor, Integer limit) {
        User user = requireByUsername(username);
        requireCanViewPosts(viewerId, user);
        int n = CursorPage.clampLimit(limit);
        List<Follow> rows = followRepository.findFollowers(user.getId(), viewerId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        return CursorPage.of(rows, n, Follow::getId,
                page -> page.stream().map(f -> userMapper.toSummary(f.getFollower())).toList());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> following(String username, Long viewerId, Long cursor, Integer limit) {
        User user = requireByUsername(username);
        requireCanViewPosts(viewerId, user);
        int n = CursorPage.clampLimit(limit);
        List<Follow> rows = followRepository.findFollowing(user.getId(), viewerId, CursorPage.cursorOrMax(cursor),
                Limit.of(n + 1));
        return CursorPage.of(rows, n, Follow::getId,
                page -> page.stream().map(f -> userMapper.toSummary(f.getFollowee())).toList());
    }

    @Transactional(readOnly = true)
    public List<UserSummary> search(String q) {
        String term = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (term.startsWith("@")) {
            term = term.substring(1);
        }
        if (term.isEmpty()) {
            return List.of();
        }
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return userRepository.search(escaped + "%", Limit.of(SEARCH_LIMIT)).stream()
                .map(userMapper::toSummary)
                .toList();
    }
}
