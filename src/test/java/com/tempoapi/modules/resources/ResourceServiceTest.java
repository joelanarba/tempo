package com.tempoapi.modules.resources;

import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Space;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.resources.dto.CreateResourceRequest;
import com.tempoapi.modules.resources.dto.ResourceResponse;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import com.tempoapi.modules.availability.AvailabilityChanges;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    @Mock
    private ResourceRepository resourceRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private AvailabilityChanges availabilityChanges;
    @InjectMocks
    private ResourceService resourceService;

    private Space space(UUID id) {
        Space space = new Space("Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(18, 0));
        space.setId(id);
        return space;
    }

    @Test
    void createDeskStoresNullCapacity() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space(spaceId)));
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResourceResponse response = resourceService.create(spaceId,
                new CreateResourceRequest("Desk 1", ResourceType.DESK, 5));

        assertThat(response.type()).isEqualTo(ResourceType.DESK);
        assertThat(response.capacity()).isNull();
    }

    @Test
    void createRoomStoresCapacity() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space(spaceId)));
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResourceResponse response = resourceService.create(spaceId,
                new CreateResourceRequest("Room A", ResourceType.ROOM, 8));

        assertThat(response.type()).isEqualTo(ResourceType.ROOM);
        assertThat(response.capacity()).isEqualTo(8);
    }

    @Test
    void createRoomWithoutCapacityIsRejected() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space(spaceId)));

        assertThatThrownBy(() -> resourceService.create(spaceId,
                new CreateResourceRequest("Room A", ResourceType.ROOM, null)))
                .isInstanceOf(ValidationException.class);

        verify(resourceRepository, never()).save(any(Resource.class));
    }

    @Test
    void createFailsWhenSpaceMissing() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resourceService.create(spaceId,
                new CreateResourceRequest("Desk 1", ResourceType.DESK, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(resourceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resourceService.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
