package com.engine.ticketbooking.config;

import com.engine.ticketbooking.entity.User;
import com.engine.ticketbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // Seed default admin if missing
        if (!userRepository.existsByUsername("admin")) {
            User admin = User.builder()
                    .id(UUID.randomUUID())
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .role("ROLE_ADMIN")
                    .build();
            userRepository.save(admin);
            log.info("Initialized default admin user: 'admin'");
        }

        // Seed default regular user if missing
        if (!userRepository.existsByUsername("user")) {
            User testUser = User.builder()
                    .id(UUID.randomUUID())
                    .username("user")
                    .password(passwordEncoder.encode("user123"))
                    .role("ROLE_USER")
                    .build();
            userRepository.save(testUser);
            log.info("Initialized default regular user: 'user'");
        }
    }
}
