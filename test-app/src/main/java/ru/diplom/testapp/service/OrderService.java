package ru.diplom.testapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.query.Criteria;
import ru.diplom.core.query.Query;
import ru.diplom.core.query.Sort;
import ru.diplom.core.repository.R2dbcGraphTemplate;
import ru.diplom.testapp.entity.Order;

/**
 * Декларативная загрузка графа заказа через {@link R2dbcGraphTemplate}.
 * Используется в качестве "опытной" реализации в эксперименте по сравнению
 * с {@link OrderManualService} (ручная агрегация).
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final R2dbcGraphTemplate graphTemplate;

    public Mono<Order> getOrderFlat(Long orderId) {
        return graphTemplate.findById(orderId, Order.class, RelationGraph.empty());
    }

    public Mono<Order> getOrderWithCustomerAndItems(Long orderId) {
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("customer")
                .include("items")
                .build();
        return graphTemplate.findById(orderId, Order.class, graph);
    }

    public Mono<Order> getFullOrderGraph(Long orderId) {
        RelationGraph graph = fullGraph(RelationGraph.FetchStrategy.JOIN);
        return graphTemplate.findById(orderId, Order.class, graph);
    }

    /** То же, что {@link #getFullOrderGraph}, но с альтернативной стратегией N+1. */
    public Mono<Order> getFullOrderGraphMultiQuery(Long orderId) {
        RelationGraph graph = fullGraph(RelationGraph.FetchStrategy.MULTI_QUERY);
        return graphTemplate.findById(orderId, Order.class, graph);
    }

    /** Поиск с фильтром по клиенту, сортировкой и постраничной выборкой. */
    public Flux<Order> findByCustomer(Long customerId, int limit, long offset) {
        Query query = Query.query(Criteria.where("customerId").is(customerId))
                .sort(Sort.by(Sort.desc("createdAt")))
                .limit(limit)
                .offset(offset);
        return graphTemplate.findAll(Order.class, query, fullGraph(RelationGraph.FetchStrategy.JOIN));
    }

    public Mono<Long> countByCustomer(Long customerId) {
        return graphTemplate.count(Order.class,
                Query.query(Criteria.where("customerId").is(customerId)));
    }

    private static RelationGraph fullGraph(RelationGraph.FetchStrategy strategy) {
        return RelationGraph.builder()
                .depth(2)
                .include("customer")
                .include("items")
                .include("items.product")
                .fetchStrategy(strategy)
                .build();
    }
}
