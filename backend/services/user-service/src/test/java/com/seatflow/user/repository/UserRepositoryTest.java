package com.seatflow.user.repository;

import com.seatflow.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_user_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindByExternalId() {
        User user = User.builder()
                .externalId("ext-repo-test")
                .email("repo-test@example.com")
                .phone("+1-555-0100")
                .build();

        User saved = userRepository.save(user);

        Optional<User> found = userRepository.findByExternalId("ext-repo-test");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isEqualTo("repo-test@example.com");
        assertThat(found.get().getPhone()).isEqualTo("+1-555-0100");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void shouldFindByEmail() {
        User user = User.builder()
                .externalId("ext-email-test")
                .email("email-test@example.com")
                .build();
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("email-test@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getExternalId()).isEqualTo("ext-email-test");
    }

    @Test
    void shouldReturnEmptyForNonExistentExternalId() {
        Optional<User> found = userRepository.findByExternalId("non-existent");
        assertThat(found).isEmpty();
    }

    @Test
    void shouldCheckExistsByExternalId() {
        User user = User.builder()
                .externalId("ext-exists-test")
                .email("exists-test@example.com")
                .build();
        userRepository.save(user);

        assertThat(userRepository.existsByExternalId("ext-exists-test")).isTrue();
        assertThat(userRepository.existsByExternalId("non-existent")).isFalse();
    }
}
