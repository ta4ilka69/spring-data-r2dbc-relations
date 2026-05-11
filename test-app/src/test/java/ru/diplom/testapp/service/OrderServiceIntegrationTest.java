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
import ru.diplom.testapp.entity.Order;

import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> postgres.getJdbcUrl().replace("jdbc", "r2dbc"));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderManualService orderManualService;

    @Test
    void shouldLoadFlatOrder() {
        orderService.getOrderFlat(1L)
                .as(StepVerifier::create)
                .assertNext(order -> {
                    assertThat(order.getId()).isEqualTo(1L);
                    assertThat(order.getCustomerId()).isEqualTo(1L);
                    assertThat(order.getCustomer()).isNull();
                    assertThat(order.getItems()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldLoadOrderWithCustomerAndItems() {
        orderService.getOrderWithCustomerAndItems(1L)
                .as(StepVerifier::create)
                .assertNext(order -> {
                    assertThat(order.getId()).isEqualTo(1L);
                    assertThat(order.getCustomer()).isNotNull();
                    assertThat(order.getCustomer().getName()).isEqualTo("Иван Иванов");
                    assertThat(order.getItems()).isNotNull().hasSize(2);
                    assertThat(order.getItems().get(0).getProduct()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void shouldLoadFullOrderGraph() {
        orderService.getFullOrderGraph(1L)
                .as(StepVerifier::create)
                .assertNext(order -> assertFullGraphLoaded(order))
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        orderService.getFullOrderGraph(999_999L)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void multiQueryStrategyProducesSameGraph() {
        Order viaJoin = orderService.getFullOrderGraph(1L).block();
        Order viaMultiQuery = orderService.getFullOrderGraphMultiQuery(1L).block();

        assertThat(viaJoin).isNotNull();
        assertThat(viaMultiQuery).isNotNull();

        assertThat(viaMultiQuery.getId()).isEqualTo(viaJoin.getId());
        assertThat(viaMultiQuery.getCustomer().getName()).isEqualTo(viaJoin.getCustomer().getName());

        var joinTitles = viaJoin.getItems().stream()
                .sorted(Comparator.comparing(i -> i.getProduct().getTitle()))
                .map(i -> i.getProduct().getTitle()).toList();
        var multiTitles = viaMultiQuery.getItems().stream()
                .sorted(Comparator.comparing(i -> i.getProduct().getTitle()))
                .map(i -> i.getProduct().getTitle()).toList();
        assertThat(multiTitles).isEqualTo(joinTitles);
    }

    @Test
    void manualAggregationProducesEquivalentGraph() {
        Order viaModule = orderService.getFullOrderGraph(1L).block();
        Order viaManual = orderManualService.getFullOrderGraph(1L).block();

        assertThat(viaModule).isNotNull();
        assertThat(viaManual).isNotNull();
        assertThat(viaManual.getId()).isEqualTo(viaModule.getId());
        assertThat(viaManual.getCustomer().getEmail()).isEqualTo(viaModule.getCustomer().getEmail());

        var moduleTitles = viaModule.getItems().stream()
                .sorted(Comparator.comparing(i -> i.getProduct().getTitle()))
                .map(i -> i.getProduct().getTitle()).toList();
        var manualTitles = viaManual.getItems().stream()
                .sorted(Comparator.comparing(i -> i.getProduct().getTitle()))
                .map(i -> i.getProduct().getTitle()).toList();
        assertThat(manualTitles).isEqualTo(moduleTitles);
    }

    @Test
    void findByCustomerSupportsLimitAndSort() {
        orderService.findByCustomer(1L, 10, 0)
                .as(StepVerifier::create)
                .assertNext(order -> {
                    assertThat(order.getCustomerId()).isEqualTo(1L);
                    assertThat(order.getCustomer()).isNotNull();
                    assertThat(order.getItems()).isNotEmpty();
                })
                .verifyComplete();
    }

    @Test
    void countMatchesNumberOfOrdersForCustomer() {
        orderService.countByCustomer(1L)
                .as(StepVerifier::create)
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void rejectsUnknownIncludePathAtGenerationTime() {
        var bogus = ru.diplom.core.graph.RelationGraph.builder()
                .include("itemz")
                .build();
        // Ошибка валидации возникает синхронно при генерации SQL — до похода в БД.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ru.diplom.core.sql.SqlGenerator()
                        .generateSelect(ru.diplom.testapp.entity.Order.class, bogus));
    }

    private static void assertFullGraphLoaded(Order order) {
        assertThat(order.getId()).isEqualTo(1L);
        assertThat(order.getCustomer()).isNotNull();
        assertThat(order.getItems()).isNotNull().hasSize(2);
        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.getProduct()).isNotNull();
            assertThat(item.getProduct().getTitle()).isNotBlank();
            assertThat(item.getProduct().getPrice()).isNotNull();
        });
    }
}
