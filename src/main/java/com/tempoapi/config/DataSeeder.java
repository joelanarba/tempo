package com.tempoapi.config;

import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

/**
 * Seeds a development environment with one admin, one space and a handful of
 * resources. Active only under the {@code setup} profile so it never runs in
 * production. The admin password comes from the environment, never the source.
 */
@Component
@Profile("setup")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String ADMIN_EMAIL = "admin@tempo.africa";

    private final UserRepository userRepository;
    private final SpaceRepository spaceRepository;
    private final ResourceRepository resourceRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      SpaceRepository spaceRepository,
                      ResourceRepository resourceRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.admin-password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.spaceRepository = spaceRepository;
        this.resourceRepository = resourceRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            log.info("Seed data already present; skipping.");
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("SEED_ADMIN_PASSWORD is not set; skipping seeding.");
            return;
        }

        User admin = new User(ADMIN_EMAIL, passwordEncoder.encode(adminPassword), "Tempo", "Admin", Role.ADMIN);
        userRepository.save(admin);

        Space space = new Space(
                "Accra Central Hub",
                "Independence Avenue, Accra",
                "Flagship co-working space in central Accra.",
                LocalTime.of(8, 0),
                LocalTime.of(20, 0));
        spaceRepository.save(space);

        for (int i = 1; i <= 5; i++) {
            resourceRepository.save(new Resource(space, "Desk " + i, ResourceType.DESK, null));
        }
        resourceRepository.save(new Resource(space, "Meeting Room A", ResourceType.ROOM, 8));
        resourceRepository.save(new Resource(space, "Meeting Room B", ResourceType.ROOM, 4));

        log.info("Seeded admin '{}', space '{}' with 5 desks and 2 rooms.", ADMIN_EMAIL, space.getName());
    }
}
