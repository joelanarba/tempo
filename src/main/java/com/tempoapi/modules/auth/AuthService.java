package com.tempoapi.modules.auth;

import com.tempoapi.entity.RefreshToken;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.User;
import com.tempoapi.exception.DuplicateResourceException;
import com.tempoapi.exception.UnauthorizedException;
import com.tempoapi.modules.auth.dto.AccessTokenResponse;
import com.tempoapi.modules.auth.dto.AuthResponse;
import com.tempoapi.modules.auth.dto.LoginRequest;
import com.tempoapi.modules.auth.dto.LogoutRequest;
import com.tempoapi.modules.auth.dto.RefreshRequest;
import com.tempoapi.modules.auth.dto.RegisterRequest;
import com.tempoapi.repository.RefreshTokenRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.security.TokenBlocklist;
import com.tempoapi.util.JwtUtil;
import com.tempoapi.util.TokenHash;
import java.nio.charset.StandardCharsets;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private static final String BEARER = "Bearer";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlocklist tokenBlocklist;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       TokenBlocklist tokenBlocklist) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.tokenBlocklist = tokenBlocklist;
    }

    /** Registers a new MEMBER, storing only the BCrypt hash of the password, and issues tokens. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new com.tempoapi.exception.ValidationException("Password must not exceed 72 UTF-8 bytes");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email is already registered");
        }
        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName(),
                Role.MEMBER);
        user = userRepository.save(user);
        return issueTokens(user);
    }

    /** Authenticates by email and password and issues a fresh token pair. */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UnauthorizedException("Invalid email or password");
        }
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        if (!user.isActive()) {
            throw new UnauthorizedException("Account is disabled");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return issueTokens(user);
    }

    /** Refresh credentials retain their original expiry and are reusable until revoked. */
    @Transactional(readOnly = true)
    public AccessTokenResponse refresh(RefreshRequest request) {
        Claims claims = parseRefreshToken(request.refreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHash.of(request.refreshToken()))
                .orElseThrow(() -> new UnauthorizedException("Refresh token is not recognised"));
        if (stored.isRevoked()) {
            throw new UnauthorizedException("Refresh token has been revoked");
        }
        if (!Instant.now().isBefore(stored.getExpiresAt())) {
            throw new UnauthorizedException("Refresh token has expired");
        }
        User user = stored.getUser();
        if (!user.isActive()) {
            throw new UnauthorizedException("Account is disabled");
        }
        // Subject in the JWT must match the owning user; guards against token/row mismatch.
        if (!jwtUtil.extractUserId(claims).equals(user.getId())) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getRole());
        return new AccessTokenResponse(accessToken, BEARER);
    }

    /** Revokes the refresh token and blocklists the presented access token until it expires. */
    @Transactional
    public void logout(String accessToken, LogoutRequest request) {
        Claims claims;
        try {
            claims = jwtUtil.parse(accessToken);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("A valid access token is required");
        }
        if (!jwtUtil.isAccessToken(claims)) {
            throw new UnauthorizedException("A valid access token is required");
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHash.of(request.refreshToken()))
                .orElseThrow(() -> new UnauthorizedException("Refresh token is not recognised"));
        if (!stored.getUser().getId().equals(jwtUtil.extractUserId(claims))) {
            throw new com.tempoapi.exception.ForbiddenException("Refresh token does not belong to this account");
        }
        tokenBlocklist.blocklist(jwtUtil.extractJti(claims), jwtUtil.extractExpiration(claims));
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);
    }

    private Claims parseRefreshToken(String token) {
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (!jwtUtil.isRefreshToken(claims)) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        return claims;
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        refreshTokenRepository.save(new RefreshToken(user, refreshToken, jwtUtil.refreshTokenExpiry()));
        return new AuthResponse(accessToken, refreshToken, BEARER,
                user.getId(), user.getEmail(), user.getRole().name());
    }
}
