package ru.diplom.testapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.diplom.testapp.entity.Customer;
import ru.diplom.testapp.entity.Order;
import ru.diplom.testapp.entity.OrderItem;
import ru.diplom.testapp.entity.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Эталонная реализация загрузки графа заказа через стандартные средства
 * Spring Data R2DBC и {@link DatabaseClient}: серия отдельных реактивных
 * запросов и ручная сборка результата.
 *
 * <p>Используется в дипломе для эксперимента по сравнению объёма и сложности
 * прикладного кода относительно декларативного {@link OrderService}.
 * Эта реализация специально не оптимизирована "хитростями" — она отражает
 * типичный код, который разработчик пишет в реальных проектах.
 */
@Service
@RequiredArgsConstructor
public class OrderManualService {

    private final DatabaseClient databaseClient;

    public Mono<Order> getOrderFlat(Long orderId) {
        return loadOrder(orderId);
    }

    public Mono<Order> getOrderWithCustomerAndItems(Long orderId) {
        return loadOrder(orderId).flatMap(order ->
                Mono.zip(loadCustomer(order.getCustomerId()), loadItems(order.getId()).collectList())
                        .map(tuple -> {
                            order.setCustomer(tuple.getT1());
                            order.setItems(tuple.getT2());
                            return order;
                        }));
    }

    public Mono<Order> getFullOrderGraph(Long orderId) {
        return loadOrder(orderId).flatMap(order ->
                Mono.zip(loadCustomer(order.getCustomerId()), loadItems(order.getId()).collectList())
                        .flatMap(tuple -> {
                            order.setCustomer(tuple.getT1());
                            List<OrderItem> items = tuple.getT2();
                            order.setItems(items);

                            Set<Long> productIds = items.stream()
                                    .map(OrderItem::getProductId)
                                    .collect(Collectors.toSet());

                            return loadProducts(productIds)
                                    .collectMap(Product::getId)
                                    .map(productById -> {
                                        items.forEach(item -> item.setProduct(productById.get(item.getProductId())));
                                        return order;
                                    });
                        }));
    }

    private Mono<Order> loadOrder(Long orderId) {
        return databaseClient.sql("SELECT id, customer_id, created_at FROM orders WHERE id = :id")
                .bind("id", orderId)
                .map((row, meta) -> Order.builder()
                        .id(row.get("id", Long.class))
                        .customerId(row.get("customer_id", Long.class))
                        .createdAt(row.get("created_at", LocalDateTime.class))
                        .build())
                .one();
    }

    private Mono<Customer> loadCustomer(Long customerId) {
        return databaseClient.sql("SELECT id, name, email FROM customers WHERE id = :id")
                .bind("id", customerId)
                .map((row, meta) -> Customer.builder()
                        .id(row.get("id", Long.class))
                        .name(row.get("name", String.class))
                        .email(row.get("email", String.class))
                        .build())
                .one();
    }

    private Flux<OrderItem> loadItems(Long orderId) {
        return databaseClient.sql("SELECT id, order_id, product_id, quantity FROM order_items WHERE order_id = :orderId")
                .bind("orderId", orderId)
                .map((row, meta) -> OrderItem.builder()
                        .id(row.get("id", Long.class))
                        .orderId(row.get("order_id", Long.class))
                        .productId(row.get("product_id", Long.class))
                        .quantity(row.get("quantity", Integer.class))
                        .build())
                .all();
    }

    private Flux<Product> loadProducts(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }
        Map<String, Object> binds = new HashMap<>();
        StringBuilder placeholders = new StringBuilder();
        int i = 0;
        for (Long id : ids) {
            if (i > 0) {
                placeholders.append(", ");
            }
            String name = "p" + i;
            placeholders.append(':').append(name);
            binds.put(name, id);
            i++;
        }
        var spec = databaseClient.sql("SELECT id, title, price FROM products WHERE id IN (" + placeholders + ")");
        for (Map.Entry<String, Object> e : binds.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        return spec.map((row, meta) -> Product.builder()
                        .id(row.get("id", Long.class))
                        .title(row.get("title", String.class))
                        .price(row.get("price", BigDecimal.class))
                        .build())
                .all();
    }
}
