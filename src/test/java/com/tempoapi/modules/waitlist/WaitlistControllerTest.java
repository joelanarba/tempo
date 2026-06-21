package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WaitlistControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SpaceRepository spaceRepository;
    @Autowired private ResourceRepository resourceRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void joiningAFreeSlotReturns400() throws Exception {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User member = persistUser(Role.MEMBER);

        String body = objectMapper.writeValueAsString(Map.of(
                "resourceId", resource.getId().toString(),
                "startTime", at(9).toString(),
                "endTime", at(10).toString()));

        mockMvc.perform(post("/api/v1/waitlist")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void joiningWithoutAuthReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
