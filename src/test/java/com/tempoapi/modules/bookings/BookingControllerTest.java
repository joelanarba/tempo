package com.tempoapi.modules.bookings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;

    private UUID spaceId;
    private UUID resourceId;

    @BeforeEach
    void seed() {
        Space space = spaceRepository.save(new Space(
                "Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(
                space, "Desk 1", ResourceType.DESK, null));
        spaceId = space.getId();
        resourceId = resource.getId();
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private String body(int startHour, int endHour) throws Exception {
        return objectMapper.writeValueAsString(
                new CreateBookingRequest(resourceId, at(startHour), at(endHour)));
    }

    @Test
    void memberCanCreateBooking() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", bearerFor(Role.MEMBER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.resourceId").value(resourceId.toString()));
    }

    @Test
    void bookingBeforeOpeningHoursReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", bearerFor(Role.MEMBER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(6, 7)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedCannotBook() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9, 10)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void memberCannotViewAnotherMembersBooking() throws Exception {
        User owner = persistUser(Role.MEMBER);
        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", bearerFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9, 10)))
                .andExpect(status().isCreated())
                .andReturn();
        String bookingId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId)
                        .header("Authorization", bearerFor(Role.MEMBER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanCancelOwnBooking() throws Exception {
        User owner = persistUser(Role.MEMBER);
        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", bearerFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9, 10)))
                .andExpect(status().isCreated())
                .andReturn();
        String bookingId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("id").asText();

        mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId)
                        .header("Authorization", bearerFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void adminCanListSpaceBookingsButMemberCannot() throws Exception {
        String date = LocalDate.now().plusDays(1).toString();

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/bookings", spaceId)
                        .param("date", date)
                        .header("Authorization", bearerFor(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/bookings", spaceId)
                        .param("date", date)
                        .header("Authorization", bearerFor(Role.MEMBER)))
                .andExpect(status().isForbidden());
    }
}
