package com.tempoapi.repository;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.util.TimeRangeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WaitlistEntryRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void savesAndDetectsActiveEntry() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);

        waitlistEntryRepository.save(new WaitlistEntry(resource, space, user,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.WAITING));

        assertThat(waitlistEntryRepository.existsActiveEntry(resource.getId(), user.getId(),
                TimeRangeUtil.toUtc(at(9)), TimeRangeUtil.toUtc(at(10)))).isTrue();
        assertThat(waitlistEntryRepository
                .findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .hasSize(1);
    }
}
