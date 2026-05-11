package ru.diplom.core.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.mapper.GraphResultSetExtractor;
import ru.diplom.core.query.Query;
import ru.diplom.core.sql.GeneratedQuery;
import ru.diplom.core.sql.SqlGenerator;

import java.util.List;
import java.util.Map;

/**
 * Главный фасад для работы с базой данных с поддержкой загрузки графа объектов.
 * Объединяет {@link SqlGenerator}, {@link GraphResultSetExtractor} и стандартный
 * реактивный {@link DatabaseClient}, не требуя отказа от инфраструктуры
 * Spring Data R2DBC.
 */
public class R2dbcGraphTemplate {

    private final DatabaseClient databaseClient;
    private final SqlGenerator sqlGenerator;
    private final GraphResultSetExtractor extractor;
    private final MultiQueryExecutor multiQueryExecutor;

    public R2dbcGraphTemplate(DatabaseClient databaseClient) {
        this(databaseClient, new SqlGenerator(), new GraphResultSetExtractor());
    }

    public R2dbcGraphTemplate(DatabaseClient databaseClient,
                              SqlGenerator sqlGenerator,
                              GraphResultSetExtractor extractor) {
        this.databaseClient = databaseClient;
        this.sqlGenerator = sqlGenerator;
        this.extractor = extractor;
        this.multiQueryExecutor = new MultiQueryExecutor(databaseClient, sqlGenerator);
    }

    public <T> Mono<T> findById(Object id, Class<T> entityClass, RelationGraph graph) {
        if (graph.getFetchStrategy() == RelationGraph.FetchStrategy.MULTI_QUERY) {
            return multiQueryExecutor.findById(id, entityClass, graph);
        }
        GeneratedQuery generated = sqlGenerator.generateSelectById(entityClass, graph);

        return databaseClient.sql(generated.getSql())
                .bind(SqlGenerator.ID_PARAMETER, id)
                .fetch()
                .all()
                .collectList()
                .flatMap(rows -> {
                    List<T> result = extractor.extractData(rows, generated.getContext());
                    return result.isEmpty() ? Mono.empty() : Mono.just(result.get(0));
                });
    }

    /**
     * Выборка всех корневых сущностей с подгрузкой графа.
     *
     * <p>Эквивалентно {@code findAll(entityClass, Query.empty(), graph)}; оставлен
     * для совместимости со старым кодом и более коротким сценарием.
     */
    public <T> Flux<T> findAll(Class<T> entityClass, RelationGraph graph) {
        return findAll(entityClass, Query.empty(), graph);
    }

    /**
     * Полноценная выборка с фильтром, сортировкой и постраничной разбивкой.
     */
    public <T> Flux<T> findAll(Class<T> entityClass, Query query, RelationGraph graph) {
        if (graph.getFetchStrategy() == RelationGraph.FetchStrategy.MULTI_QUERY) {
            return multiQueryExecutor.findAll(entityClass, query, graph);
        }
        GeneratedQuery generated = sqlGenerator.generateSelectByQuery(entityClass, graph, query);
        return executeAndExtract(generated);
    }

    /** Возвращает первый результат запроса или {@link Mono#empty()}. */
    public <T> Mono<T> findOne(Class<T> entityClass, Query query, RelationGraph graph) {
        Query limited = query.hasLimit() ? query : query.limit(1);
        return findAll(entityClass, limited, graph).next();
    }

    /** Подсчёт корневых сущностей по тому же критерию (без JOIN-ов). */
    public Mono<Long> count(Class<?> entityClass, Query query) {
        GeneratedQuery generated = sqlGenerator.generateCount(entityClass, query);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(generated.getSql());
        for (Map.Entry<String, Object> e : generated.getBindings().entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        return spec.map((row, meta) -> {
            Number n = row.get("total", Number.class);
            return n == null ? 0L : n.longValue();
        }).one();
    }

    private <T> Flux<T> executeAndExtract(GeneratedQuery generated) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(generated.getSql());
        for (Map.Entry<String, Object> e : generated.getBindings().entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        return spec.fetch()
                .all()
                .collectList()
                .flatMapIterable(rows -> extractor.extractData(rows, generated.getContext()));
    }
}
