package com.tempoapi.modules.auth;

import com.tempoapi.config.JwtProperties;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private TokenBlocklist tokenBlocklist;
    @Captor
    private ArgumentCaptor<User> userCaptor;

    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(12);
        JwtProperties properties = new JwtProperties();
        properties.setSecret("unit-test-secret-unit-test-secret-unit-test-secret");
        properties.setAccessExpiryMs(900_000);
        properties.setRefreshExpiryMs(604_800_000);
        jwtUtil = new JwtUtil(properties);
        authService = new AuthService(userRepository, refreshTokenRepository,
                passwordEncoder, jwtUtil, tokenBlocklist);
    }

    @Test
    void registerStoresBcryptHashNeverThePlaintext() {
        String rawPassword = "Sup3rSecret!";
        when(userRepository.existsByEmail("ada@tempo.africa")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User toSave = invocation.getArgument(0);
            if (toSave.getId() == null) {
                toSave.setId(UUID.randomUUID());
            }
            return toSave;
        });

        AuthResponse response = authService.register(
                new RegisterRequest("ada@tempo.africa", rawPassword, "Ada", "Obi"));

        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(rawPassword, saved.getPasswordHash())).isTrue();
        assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@tempo.africa")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("dup@tempo.africa", "password1", "Dup", "User")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        User user = persistedUser("kofi@tempo.africa", "Pa55word!");
        when(userRepository.findByEmail("kofi@tempo.africa")).thenReturn(Optional.of(user));

        AuthResponse response = authService.login(new LoginRequest("kofi@tempo.africa", "Pa55word!"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.userId()).isEqualTo(user.getId());
    }

    @Test
    void loginFailsWithWrongPassword() {
        User user = persistedUser("kofi@tempo.africa", "Pa55word!");
        when(userRepository.findByEmail("kofi@tempo.africa")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("kofi@tempo.africa", "wrong")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void loginFailsForUnknownEmail() {
        when(userRepository.findByEmail("ghost@tempo.africa")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@tempo.africa", "whatever")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refreshIssuesNewAccessTokenForValidStoredToken() {
        User user = persistedUser("ama@tempo.africa", "Pa55word!");
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        RefreshToken stored = new RefreshToken(user, refreshToken, Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(stored));

        AccessTokenResponse response = authService.refresh(new RefreshRequest(refreshToken));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(jwtUtil.isAccessToken(jwtUtil.parse(response.accessToken()))).isTrue();
    }

    @Test
    void refreshFailsForRevokedToken() {
        User user = persistedUser("ama@tempo.africa", "Pa55word!");
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        RefreshToken stored = new RefreshToken(user, refreshToken, Instant.now().plusSeconds(3600));
        stored.setRevoked(true);
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(refreshToken)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refreshFailsWhenAccessTokenSuppliedInsteadOfRefresh() {
        User user = persistedUser("ama@tempo.africa", "Pa55word!");
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getRole());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(accessToken)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void logoutRevokesRefreshTokenAndBlocklistsAccessToken() {
        User user = persistedUser("ama@tempo.africa", "Pa55word!");
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getRole());
        RefreshToken stored = new RefreshToken(user, refreshToken, Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(stored));

        authService.logout(accessToken, new LogoutRequest(refreshToken));

        assertThat(stored.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(stored);
        verify(tokenBlocklist).blocklist(any(String.class), any(Instant.class));
    }

    private User persistedUser(String email, String rawPassword) {
        User user = new User(email, passwordEncoder.encode(rawPassword), "First", "Last", Role.MEMBER);
        user.setId(UUID.randomUUID());
        return user;
    }
}
