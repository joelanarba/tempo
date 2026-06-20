package com.tempoapi.modules.spaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Role;
import com.tempoapi.modules.spaces.dto.CreateSpaceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaceControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(new CreateSpaceRequest(
                "Accra Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0)));
    }

    @Test
    void adminCanCreateSpace() throws Exception {
        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", bearerFor(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void memberCannotCreateSpace() throws Exception {
        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", bearerFor(Role.MEMBER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotCreateSpace() throws Exception {
        mockMvc.perform(post("/api/v1/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void memberCanListSpacesWithPaginationShape() throws Exception {
        mockMvc.perform(post("/api/v1/spaces")
                .header("Authorization", bearerFor(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()));

        mockMvc.perform(get("/api/v1/spaces").header("Authorization", bearerFor(Role.MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.page").exists())
                .andExpect(jsonPath("$.data.size").exists())
                .andExpect(jsonPath("$.data.totalElements").exists())
                .andExpect(jsonPath("$.data.totalPages").exists())
                .andExpect(jsonPath("$.data.last").exists());
    }

    @Test
    void invalidHoursAreRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateSpaceRequest(
                "Bad", "Accra", "desc", LocalTime.of(20, 0), LocalTime.of(8, 0)));
        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", bearerFor(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
