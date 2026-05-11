package ru.diplom.testapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;
import ru.diplom.testapp.entity.Tag;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что модуль корректно обрабатывает не-Long идентификаторы.
 *
 * <p>Входная точка — сущность {@link Tag} с {@code UUID}-первичным ключом и
 * {@code ONE_TO_MANY}-связью {@code audits}, где FK тоже UUID.
 */
@SpringBootTest
@Testcontainers
class UuidIdIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> postgres.getJdbcUrl().replace("jdbc", "r2dbc"));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    private static final UUID ELECTRONICS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOOKS = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private TagService tagService;

    @Test
    void loadsTagByUuidWithOneToManyChildren() {
        tagService.getTagWithAudits(ELECTRONICS)
                .as(StepVerifier::create)
                .assertNext(tag -> {
                    assertThat(tag.getId()).isEqualTo(ELECTRONICS);
                    assertThat(tag.getName()).isEqualTo("electronics");
                    assertThat(tag.getAudits()).hasSize(2);
                    assertThat(tag.getAudits())
                            .extracting(a -> a.getTagId())
                            .containsOnly(ELECTRONICS);
                })
                .verifyComplete();
    }

    @Test
    void multiQueryStrategyAlsoWorksForUuidKeys() {
        // MULTI_QUERY формирует WHERE tag_id IN (?) по collection UUID-ов —
        // это самый хрупкий путь, поэтому вытягиваем именно его.
        tagService.getTagWithAuditsMultiQuery(BOOKS)
                .as(StepVerifier::create)
                .assertNext(tag -> {
                    assertThat(tag.getId()).isEqualTo(BOOKS);
                    assertThat(tag.getName()).isEqualTo("books");
                    assertThat(tag.getAudits()).hasSize(1);
                    assertThat(tag.getAudits().get(0).getTagId()).isEqualTo(BOOKS);
                })
                .verifyComplete();
    }

    @Test
    void findByNameReturnsCorrectTag() {
        tagService.findByName("electronics")
                .as(StepVerifier::create)
                .assertNext(tag -> assertThat(tag.getId()).isEqualTo(ELECTRONICS))
                .verifyComplete();
    }
}
