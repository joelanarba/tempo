package com.tempoapi.config;

import com.tempoapi.entity.User;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DataSeederTest {
    @Mock UserRepository users;
    @Mock SpaceRepository spaces;
    @Mock ResourceRepository resources;

    @Test
    void generatedSizePasswordProducesAUsableAdministratorHash() {
        String password = "a".repeat(48);
        var encoder = new BCryptPasswordEncoder(4);
        new DataSeeder(users, spaces, resources, encoder, password).run();
        var captured = ArgumentCaptor.forClass(User.class);
        verify(users).save(captured.capture());
        assertThat(encoder.matches(password, captured.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void oversizedUtf8PasswordIsRejectedBeforeSavingAnything() {
        var seeder = new DataSeeder(users, spaces, resources, new BCryptPasswordEncoder(4), "\u00e9".repeat(37));
        assertThatThrownBy(seeder::run).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(spaces, resources);
    }
}
