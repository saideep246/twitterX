package com.project.Xclone_backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.Xclone_backend.auth.AuthDtos.AuthResponse;
import com.project.Xclone_backend.auth.AuthDtos.LoginRequest;
import com.project.Xclone_backend.auth.AuthDtos.RegisterRequest;
import com.project.Xclone_backend.common.ApiException;
import com.project.Xclone_backend.config.JwtProperties;
import com.project.Xclone_backend.postevent.PostEvent;
import com.project.Xclone_backend.security.JwtService;
import com.project.Xclone_backend.user.AccountStatus;
import com.project.Xclone_backend.user.User;
import com.project.Xclone_backend.user.UserMapper;
import com.project.Xclone_backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UserMapper userMapper;
    private final EmailTokenService emailTokenService;
    private final ApplicationEventPublisher events;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String username = req.username().toLowerCase(Locale.ROOT);
        String email = req.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("Username is already taken");
        }
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("Email is already registered");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setDisplayName(req.displayName().strip());
        user.setEmailVerified(false);
        userRepository.save(user);
        emailTokenService.sendVerification(user);
        return issueTokens(user);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        emailTokenService.consume(rawToken, EmailTokenType.VERIFY_EMAIL).setEmailVerified(true);
    }

    /** Silent when the email is unknown or the account is not active, so it cannot be used to probe for accounts. */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE)
                .ifPresent(emailTokenService::sendPasswordReset);
    }

    /** Also signs out every session, like a password change. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        User user = emailTokenService.consume(rawToken, EmailTokenType.RESET_PASSWORD);
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw ApiException.badRequest("Invalid or expired token");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String id = req.usernameOrEmail().strip().toLowerCase(Locale.ROOT);
        User user = (id.contains("@") ? userRepository.findByEmail(id) : userRepository.findByUsername(id))
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));
        // Checked only after the password matches, so a wrong password never changes or reveals the status.
        if (user.getStatus() == AccountStatus.DEACTIVATED) {
            user.setStatus(AccountStatus.ACTIVE);
            events.publishEvent(PostEvent.accountChanged(user.getId())); // their posts are visible again
        } else if (user.getStatus() == AccountStatus.SUSPENDED) {
            throw ApiException.forbidden("Account suspended");
        } else if (user.getStatus() != AccountStatus.ACTIVE) {
            throw ApiException.unauthorized("Invalid credentials");
        }
        return issueTokens(user);
    }

    /** Rotates the refresh token: the presented token is revoked and a new pair is issued. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (token.isRevoked()) {
            // Reuse of a rotated token suggests theft: kill every session for this user.
            refreshTokenRepository.revokeAllForUser(token.getUser().getId());
            throw ApiException.unauthorized("Invalid refresh token");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Refresh token expired");
        }
        if (token.getUser().getStatus() != AccountStatus.ACTIVE) {
            throw ApiException.unauthorized("Invalid refresh token");
        }
        token.setRevoked(true);
        return issueTokens(token.getUser());
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(t -> t.setRevoked(true));
    }

    private AuthResponse issueTokens(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(raw));
        token.setExpiresAt(Instant.now().plus(jwtProperties.refreshTtl()));
        refreshTokenRepository.save(token);

        String access = jwtService.createAccessToken(user.getId(), user.getUsername());
        return new AuthResponse(access, raw, jwtService.accessTtlSeconds(), userMapper.toResponse(user));
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
