package ru.diplom.testapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.query.Criteria;
import ru.diplom.core.query.Query;
import ru.diplom.core.repository.R2dbcGraphTemplate;
import ru.diplom.testapp.entity.Tag;

import java.util.UUID;

/**
 * Демонстрационный сервис поверх {@link Tag}, показывающий, что модуль
 * одинаково работает с идентификаторами типа UUID и с обычными числовыми.
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final R2dbcGraphTemplate graphTemplate;

    /** Загрузка тега вместе с историей изменений (ONE_TO_MANY через UUID-FK). */
    public Mono<Tag> getTagWithAudits(UUID tagId) {
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("audits")
                .build();
        return graphTemplate.findById(tagId, Tag.class, graph);
    }

    /** То же, но по стратегии {@code MULTI_QUERY}. */
    public Mono<Tag> getTagWithAuditsMultiQuery(UUID tagId) {
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("audits")
                .fetchStrategy(RelationGraph.FetchStrategy.MULTI_QUERY)
                .build();
        return graphTemplate.findById(tagId, Tag.class, graph);
    }

    /** Поиск тегов по имени — проверяет работу Criteria/UUID в SELECT. */
    public Flux<Tag> findByName(String name) {
        Query query = Query.query(Criteria.where("name").is(name));
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("audits")
                .build();
        return graphTemplate.findAll(Tag.class, query, graph);
    }
}
