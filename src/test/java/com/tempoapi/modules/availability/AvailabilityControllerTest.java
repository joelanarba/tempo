package com.tempoapi.modules.availability;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvailabilityControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private BookingService bookingService;

    private UUID spaceId;
    private UUID resourceId;

    @BeforeEach
    void seed() {
        Space space = spaceRepository.save(new Space(
                "Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        spaceId = space.getId();
        resourceId = resource.getId();
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void snapshotListsResourcesWithEmptySlotsWhenNothingBooked() throws Exception {
        String date = LocalDate.now().plusDays(1).toString();
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/availability", spaceId)
                        .param("date", date)
                        .header("Authorization", bearerFor(Role.MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.data.resources").isArray())
                .andExpect(jsonPath("$.data.resources[0].resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$.data.resources[0].bookedSlots").isEmpty());
    }

    @Test
    void snapshotShowsConfirmedSlotsAfterBooking() throws Exception {
        User member = persistUser(Role.MEMBER);
        bookingService.createBooking(member.getId(), new CreateBookingRequest(resourceId, at(9), at(10)));

        String date = LocalDate.now().plusDays(1).toString();
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/availability", spaceId)
                        .param("date", date)
                        .header("Authorization", bearerFor(Role.MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources[0].bookedSlots[0].startTime").exists())
                .andExpect(jsonPath("$.data.resources[0].bookedSlots[0].endTime").exists());
    }

    @Test
    void snapshotRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/availability", spaceId)
                        .param("date", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isUnauthorized());
    }
}
