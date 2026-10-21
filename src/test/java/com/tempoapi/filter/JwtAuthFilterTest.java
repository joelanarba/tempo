package com.tempoapi.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.config.JwtProperties;
import com.tempoapi.entity.Role;
import com.tempoapi.exception.ServiceUnavailableException;
import com.tempoapi.security.RestAuthenticationEntryPoint;
import com.tempoapi.security.TokenBlocklist;
import com.tempoapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void revocationOutageReturns503BeforeProtectedHandler() throws Exception {
        SecurityContextHolder.clearContext();
        JwtProperties properties = new JwtProperties();
        properties.setSecret(UUID.randomUUID().toString() + UUID.randomUUID());
        properties.setAccessExpiryMs(60000);
        JwtUtil jwt = new JwtUtil(properties);
        TokenBlocklist blocklist = mock(TokenBlocklist.class);
        when(blocklist.isBlocklisted(anyString())).thenThrow(new ServiceUnavailableException("offline"));
        JwtAuthFilter filter = new JwtAuthFilter(jwt, blocklist, new RestAuthenticationEntryPoint(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/protected");
        request.addHeader("Authorization", "Bearer " + jwt.generateAccessToken(UUID.randomUUID(), Role.MEMBER));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("success").asBoolean()).isFalse();
        assertThat(called).isFalse();
    }
}
