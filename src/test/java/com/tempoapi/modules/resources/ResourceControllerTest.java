package com.tempoapi.modules.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.modules.resources.dto.CreateResourceRequest;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SpaceRepository spaceRepository;

    private UUID spaceId;

    @BeforeEach
    void seedSpace() {
        Space space = spaceRepository.save(new Space(
                "Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        spaceId = space.getId();
    }

    @Test
    void adminCanCreateDesk() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateResourceRequest("Desk 1", ResourceType.DESK, null));
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/resources", spaceId)
                        .header("Authorization", bearerFor(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("DESK"))
                .andExpect(jsonPath("$.data.spaceId").value(spaceId.toString()));
    }

    @Test
    void memberCannotCreateResource() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateResourceRequest("Desk 1", ResourceType.DESK, null));
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/resources", spaceId)
                        .header("Authorization", bearerFor(Role.MEMBER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void roomWithoutCapacityIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateResourceRequest("Room A", ResourceType.ROOM, null));
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/resources", spaceId)
                        .header("Authorization", bearerFor(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memberCanListResourcesWithPaginationShape() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateResourceRequest("Desk 1", ResourceType.DESK, null));
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/resources", spaceId)
                .header("Authorization", bearerFor(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/resources", spaceId)
                        .header("Authorization", bearerFor(Role.MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").exists());
    }
}
