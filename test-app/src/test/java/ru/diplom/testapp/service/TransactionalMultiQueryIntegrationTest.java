package ru.diplom.testapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.repository.R2dbcGraphTemplate;
import ru.diplom.testapp.entity.Tag;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты, проверяющие корректность совместной работы модуля с
 * реактивным транзакционным менеджером Spring.
 *
 * <p>Ключевая гипотеза: стратегия {@code MULTI_QUERY} выполняет несколько SQL
 * последовательно и должна использовать один и тот же соединение при
 * активной транзакции. Если бы это было не так, под-запрос «потерял» бы
 * незакоммиченный {@code INSERT} и тест бы упал.
 */
@SpringBootTest
@Testcontainers
@Import(TransactionalMultiQueryIntegrationTest.TxTestConfig.class)
class TransactionalMultiQueryIntegrationTest {

    @TestConfiguration
    static class TxTestConfig {
        @Bean
        TransactionalOperator transactionalOperator(ReactiveTransactionManager txManager) {
            return TransactionalOperator.create(txManager);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> postgres.getJdbcUrl().replace("jdbc", "r2dbc"));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Autowired
    private R2dbcGraphTemplate graphTemplate;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @Test
    void multiQuerySeesUncommittedInsertsWithinSameTransaction() {
        UUID newId = UUID.randomUUID();
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("audits")
                .fetchStrategy(RelationGraph.FetchStrategy.MULTI_QUERY)
                .build();

        Mono<Tag> loadedInsideTx = databaseClient.sql("INSERT INTO tags (id, name) VALUES (:id, :name)")
                .bind("id", newId)
                .bind("name", "tx-tag")
                .then()
                .then(databaseClient.sql("INSERT INTO tag_audits (tag_id, note) VALUES (:id, :note)")
                        .bind("id", newId)
                        .bind("note", "tx-created")
                        .then())
                .then(Mono.defer(() -> graphTemplate.findById(newId, Tag.class, graph)))
                .as(transactionalOperator::transactional);

        loadedInsideTx
                .as(StepVerifier::create)
                .assertNext(tag -> {
                    assertThat(tag.getId()).isEqualTo(newId);
                    assertThat(tag.getName()).isEqualTo("tx-tag");
                    assertThat(tag.getAudits()).hasSize(1);
                    assertThat(tag.getAudits().get(0).getNote()).isEqualTo("tx-created");
                })
                .verifyComplete();

        cleanup(newId);
    }

    @Test
    void rollbackDiscardsInsertsMadeWithinMultiQueryLoad() {
        UUID rollbackId = UUID.randomUUID();
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("audits")
                .fetchStrategy(RelationGraph.FetchStrategy.MULTI_QUERY)
                .build();

        RuntimeException trigger = new RuntimeException("intentional rollback");

        Mono<Tag> insertLoadAndFail = databaseClient.sql("INSERT INTO tags (id, name) VALUES (:id, :name)")
                .bind("id", rollbackId)
                .bind("name", "rollback-tag")
                .then()
                .then(Mono.defer(() -> graphTemplate.findById(rollbackId, Tag.class, graph)))
                .flatMap(tag -> Mono.<Tag>error(trigger))
                .as(transactionalOperator::transactional);

        insertLoadAndFail
                .as(StepVerifier::create)
                .expectErrorMatches(t -> t == trigger)
                .verify();

        // После rollback-а тега в БД быть не должно.
        graphTemplate.findById(rollbackId, Tag.class, RelationGraph.empty())
                .as(StepVerifier::create)
                .verifyComplete();
    }

    private void cleanup(UUID tagId) {
        databaseClient.sql("DELETE FROM tag_audits WHERE tag_id = :id")
                .bind("id", tagId)
                .then()
                .then(databaseClient.sql("DELETE FROM tags WHERE id = :id")
                        .bind("id", tagId)
                        .then())
                .block();
    }
}
