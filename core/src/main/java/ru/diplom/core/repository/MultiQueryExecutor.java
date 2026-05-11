package ru.diplom.core.repository;

import org.springframework.beans.BeanUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.metadata.ColumnMetadata;
import ru.diplom.core.metadata.EntityMetadata;
import ru.diplom.core.metadata.RelationMetadata;
import ru.diplom.core.query.Criteria;
import ru.diplom.core.query.Query;
import ru.diplom.core.sql.BindMarkers;
import ru.diplom.core.sql.SqlGenerator;

import io.r2dbc.spi.Readable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Альтернативный исполнитель запросов: вместо одного SQL с {@code LEFT JOIN}
 * выполняет отдельный запрос на каждую связь, переданную в графе. Тем самым
 * избегает «декартова взрыва» при одновременной загрузке нескольких
 * {@code ONE_TO_MANY} коллекций ценой N+1 round-trip к базе.
 *
 * <p>Выполнение разнесено по уровням: загружаются корневые сущности, после
 * чего параллельно стартуют запросы на связи первого уровня, по их
 * результатам — связи второго уровня, и так далее в пределах
 * {@link RelationGraph#getMaxDepth()}.
 */
class MultiQueryExecutor {

    private final DatabaseClient databaseClient;
    private final SqlGenerator sqlGenerator;
    private final ConversionService conversionService = DefaultConversionService.getSharedInstance();

    MultiQueryExecutor(DatabaseClient databaseClient, SqlGenerator sqlGenerator) {
        this.databaseClient = databaseClient;
        this.sqlGenerator = sqlGenerator;
    }

    <T> Mono<T> findById(Object id, Class<T> entityClass, RelationGraph graph) {
        EntityMetadata rootMeta = sqlGenerator.getMetadata(entityClass);
        Criteria byId = Criteria.where(rootMeta.getIdFieldName()).is(id);
        return findAll(entityClass, Query.query(byId).limit(1), graph).next();
    }

    <T> Flux<T> findAll(Class<T> entityClass, Query query, RelationGraph graph) {
        EntityMetadata rootMeta = sqlGenerator.getMetadata(entityClass);

        return loadEntities(rootMeta, query)
                .collectList()
                .flatMapMany(roots -> {
                    if (roots.isEmpty()) {
                        return Flux.empty();
                    }
                    return loadRelations(rootMeta, roots, "", 1, graph)
                            .thenMany(Flux.fromIterable(castList(roots)));
                });
    }

    /**
     * Загружает связи всех {@code parents} согласно графу и подставляет
     * результаты обратно в родительские объекты.
     */
    private Mono<Void> loadRelations(EntityMetadata parentMeta,
                                     List<Object> parents,
                                     String currentPath,
                                     int currentDepth,
                                     RelationGraph graph) {
        if (currentDepth > graph.getMaxDepth() || parents.isEmpty()) {
            return Mono.empty();
        }

        List<Mono<Void>> tasks = new ArrayList<>();
        for (Map.Entry<String, RelationMetadata> entry : parentMeta.getRelations().entrySet()) {
            String fieldName = entry.getKey();
            RelationMetadata relation = entry.getValue();
            String relationPath = currentPath.isEmpty() ? fieldName : currentPath + '.' + fieldName;
            if (!graph.includes(relationPath)) {
                continue;
            }
            tasks.add(loadRelation(parentMeta, parents, relation, relationPath, currentDepth, graph));
        }
        return Mono.when(tasks);
    }

    private Mono<Void> loadRelation(EntityMetadata parentMeta,
                                    List<Object> parents,
                                    RelationMetadata relation,
                                    String relationPath,
                                    int currentDepth,
                                    RelationGraph graph) {
        EntityMetadata targetMeta = sqlGenerator.getMetadata(relation.getTargetType());

        switch (relation.getRelationType()) {
            case MANY_TO_ONE:
            case ONE_TO_ONE:
                return loadOwning(parentMeta, parents, relation, targetMeta)
                        .flatMap(children -> loadRelations(targetMeta, children, relationPath, currentDepth + 1, graph));
            case ONE_TO_MANY:
                return loadInverse(parentMeta, parents, relation, targetMeta)
                        .flatMap(children -> loadRelations(targetMeta, children, relationPath, currentDepth + 1, graph));
            default:
                return Mono.empty();
        }
    }

    /**
     * Загрузка {@code MANY_TO_ONE}/{@code ONE_TO_ONE}: собираем FK из родителей,
     * читаем целевые сущности по {@code id IN (...)}, раскладываем в поля.
     */
    private Mono<List<Object>> loadOwning(EntityMetadata parentMeta,
                                          List<Object> parents,
                                          RelationMetadata relation,
                                          EntityMetadata targetMeta) {
        String fkColumn = relation.getJoinColumn();
        if (fkColumn == null || fkColumn.isEmpty()) {
            return Mono.error(new IllegalStateException(
                    "Relation " + relation.getFieldName() + " requires non-empty joinColumn"));
        }
        ColumnMetadata fkInParent = parentMeta.getColumns().stream()
                .filter(c -> c.getColumnName().equals(fkColumn))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "joinColumn '" + fkColumn + "' not found among columns of "
                                + parentMeta.getEntityType().getName()));

        Set<Object> fkValues = new HashSet<>();
        for (Object parent : parents) {
            Object fk = readField(fkInParent.getField(), parent);
            if (fk != null) {
                fkValues.add(fk);
            }
        }
        if (fkValues.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }

        Query q = Query.query(Criteria.where(targetMeta.getIdFieldName()).in(fkValues));
        return loadEntities(targetMeta, q)
                .collectMap(t -> readField(targetMeta.getIdColumn().getField(), t))
                .map(byId -> {
                    for (Object parent : parents) {
                        Object fk = readField(fkInParent.getField(), parent);
                        Object child = byId.get(fk);
                        if (child != null) {
                            writeField(relation.getField(), parent, child);
                        }
                    }
                    return new ArrayList<>(byId.values());
                });
    }

    /**
     * Загрузка {@code ONE_TO_MANY}: одним запросом по {@code <fk_column> IN (parent_ids)},
     * затем группировка по родителю и присвоение в коллекцию.
     */
    private Mono<List<Object>> loadInverse(EntityMetadata parentMeta,
                                           List<Object> parents,
                                           RelationMetadata relation,
                                           EntityMetadata targetMeta) {
        String mappedBy = relation.getMappedBy();
        if (mappedBy == null || mappedBy.isEmpty()) {
            return Mono.error(new IllegalStateException(
                    "Relation " + relation.getFieldName() + " of type ONE_TO_MANY requires non-empty mappedBy"));
        }
        ColumnMetadata fkInChild = targetMeta.getColumnByFieldName(mappedBy);
        if (fkInChild == null) {
            return Mono.error(new IllegalStateException(
                    "mappedBy '" + mappedBy + "' is not a known field of "
                            + targetMeta.getEntityType().getName()));
        }

        Field idField = parentMeta.getIdColumn().getField();
        Set<Object> parentIds = new HashSet<>();
        Map<Object, Object> parentById = new LinkedHashMap<>();
        for (Object parent : parents) {
            Object id = readField(idField, parent);
            if (id != null) {
                parentIds.add(id);
                parentById.put(id, parent);
                ensureCollectionInitialized(parent, relation);
            }
        }
        if (parentIds.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }

        Query q = Query.query(Criteria.where(mappedBy).in(parentIds));
        return loadEntities(targetMeta, q)
                .collectList()
                .map(children -> {
                    for (Object child : children) {
                        Object fk = readField(fkInChild.getField(), child);
                        Object parent = parentById.get(fk);
                        if (parent != null) {
                            appendToCollection(parent, relation, child);
                        }
                    }
                    return children;
                });
    }

    private Flux<Object> loadEntities(EntityMetadata meta, Query query) {
        BindMarkers bindMarkers = new BindMarkers();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < meta.getColumns().size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(meta.getColumns().get(i).getColumnName());
        }
        sql.append(" FROM ").append(meta.getTableName());
        if (query.hasCriteria()) {
            sql.append(" WHERE ");
            renderCriteria(sql, query.getCriteria(), meta, bindMarkers);
        }
        if (query.getSort().isSorted()) {
            sql.append(" ORDER BY ");
            for (int i = 0; i < query.getSort().getOrders().size(); i++) {
                if (i > 0) sql.append(", ");
                var order = query.getSort().getOrders().get(i);
                ColumnMetadata column = meta.getColumnByFieldName(order.getFieldName());
                if (column == null) {
                    throw new IllegalArgumentException("Unknown field for ORDER BY: " + order.getFieldName());
                }
                sql.append(column.getColumnName()).append(' ').append(order.getDirection().name());
            }
        }
        if (query.hasLimit()) {
            sql.append(" LIMIT ").append(query.getLimit());
        }
        if (query.hasOffset()) {
            sql.append(" OFFSET ").append(query.getOffset());
        }

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString());
        for (Map.Entry<String, Object> e : bindMarkers.getBindings().entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        return spec.map((row, rowMeta) -> instantiate(meta, row)).all();
    }

    private void renderCriteria(StringBuilder sql, Criteria criteria, EntityMetadata meta, BindMarkers bindMarkers) {
        criteria.accept(new Criteria.Visitor<Void>() {
            @Override
            public Void visit(Criteria.Comparison comparison) {
                ColumnMetadata column = meta.getColumnByFieldName(comparison.getFieldName());
                if (column == null) {
                    throw new IllegalArgumentException(
                            "Unknown field in criteria: " + comparison.getFieldName());
                }
                sql.append(column.getColumnName());
                Criteria.ComparisonOperator op = comparison.getOperator();
                if (op == Criteria.ComparisonOperator.IS_NULL || op == Criteria.ComparisonOperator.IS_NOT_NULL) {
                    sql.append(' ').append(op.sql());
                    return null;
                }
                if (op == Criteria.ComparisonOperator.IN) {
                    Collection<?> values = (Collection<?>) comparison.getValue();
                    sql.append(" IN (");
                    boolean first = true;
                    for (Object v : values) {
                        if (!first) sql.append(", ");
                        sql.append(':').append(bindMarkers.next(v));
                        first = false;
                    }
                    sql.append(')');
                    return null;
                }
                sql.append(' ').append(op.sql()).append(" :").append(bindMarkers.next(comparison.getValue()));
                return null;
            }

            @Override
            public Void visit(Criteria.Composite composite) {
                String separator = " " + composite.getOperator().name() + " ";
                sql.append('(');
                for (int i = 0; i < composite.getParts().size(); i++) {
                    if (i > 0) sql.append(separator);
                    composite.getParts().get(i).accept(this);
                }
                sql.append(')');
                return null;
            }
        });
    }

    private Object instantiate(EntityMetadata meta, Readable row) {
        Object instance = BeanUtils.instantiateClass(meta.getEntityType());
        for (ColumnMetadata column : meta.getColumns()) {
            Object value = row.get(column.getColumnName());
            if (value == null) {
                continue;
            }
            Field field = column.getField();
            if (!field.getType().isInstance(value)
                    && conversionService.canConvert(value.getClass(), field.getType())) {
                value = conversionService.convert(value, field.getType());
            }
            writeField(field, instance, value);
        }
        for (RelationMetadata relation : meta.getRelations().values()) {
            if (relation.isCollection()) {
                ensureCollectionInitialized(instance, relation);
            }
        }
        return instance;
    }

    private void ensureCollectionInitialized(Object owner, RelationMetadata relation) {
        Field field = relation.getField();
        try {
            field.setAccessible(true);
            Object current = field.get(owner);
            if (current != null) {
                return;
            }
            if (Set.class.isAssignableFrom(field.getType())) {
                field.set(owner, new HashSet<>());
            } else {
                field.set(owner, new ArrayList<>());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Failed to initialize collection for field " + field.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendToCollection(Object owner, RelationMetadata relation, Object child) {
        Field field = relation.getField();
        try {
            field.setAccessible(true);
            Collection<Object> collection = (Collection<Object>) field.get(owner);
            if (collection == null) {
                collection = new ArrayList<>();
                field.set(owner, collection);
            }
            if (!collection.contains(child)) {
                collection.add(child);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Could not write child into collection field " + field.getName(), e);
        }
    }

    private Object readField(Field field, Object instance) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read field " + field.getName(), e);
        }
    }

    private void writeField(Field field, Object instance, Object value) {
        try {
            field.setAccessible(true);
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not write field " + field.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(List<Object> objects) {
        return (List<T>) objects;
    }
}
