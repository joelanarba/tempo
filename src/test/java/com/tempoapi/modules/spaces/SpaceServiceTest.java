package com.tempoapi.modules.spaces;

import com.tempoapi.entity.Space;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.spaces.dto.CreateSpaceRequest;
import com.tempoapi.modules.spaces.dto.SpaceResponse;
import com.tempoapi.modules.spaces.dto.UpdateSpaceRequest;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
class SpaceServiceTest {

    @Mock
    private SpaceRepository spaceRepository;
    @InjectMocks
    private SpaceService spaceService;

    @Test
    void createPersistsSpace() {
        when(spaceRepository.save(any(Space.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SpaceResponse response = spaceService.create(new CreateSpaceRequest(
                "Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(18, 0)));

        assertThat(response.name()).isEqualTo("Hub");
        assertThat(response.openingTime()).isEqualTo(LocalTime.of(8, 0));
        verify(spaceRepository).save(any(Space.class));
    }

    @Test
    void createRejectsClosingBeforeOpening() {
        assertThatThrownBy(() -> spaceService.create(new CreateSpaceRequest(
                "Hub", "Accra", "desc", LocalTime.of(18, 0), LocalTime.of(8, 0))))
                .isInstanceOf(ValidationException.class);

        verify(spaceRepository, never()).save(any(Space.class));
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(spaceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spaceService.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateChangesMutableFields() {
        UUID id = UUID.randomUUID();
        Space space = new Space("Old", "Old loc", "old", LocalTime.of(7, 0), LocalTime.of(19, 0));
        when(spaceRepository.findById(id)).thenReturn(Optional.of(space));

        SpaceResponse response = spaceService.update(id, new UpdateSpaceRequest(
                "New", "New loc", "new", LocalTime.of(9, 0), LocalTime.of(17, 0)));

        assertThat(response.name()).isEqualTo("New");
        assertThat(space.getLocation()).isEqualTo("New loc");
        assertThat(space.getClosingTime()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    void deactivateSetsInactive() {
        UUID id = UUID.randomUUID();
        Space space = new Space("Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(18, 0));
        when(spaceRepository.findById(id)).thenReturn(Optional.of(space));

        spaceService.deactivate(id);

        assertThat(space.isActive()).isFalse();
    }
}
