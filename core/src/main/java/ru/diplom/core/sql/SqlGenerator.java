package ru.diplom.core.sql;

import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.metadata.ColumnMetadata;
import ru.diplom.core.metadata.EntityMetadata;
import ru.diplom.core.metadata.MetadataParser;
import ru.diplom.core.metadata.RelationMetadata;
import ru.diplom.core.query.Criteria;
import ru.diplom.core.query.Query;
import ru.diplom.core.query.Sort;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Генератор SQL-запросов для выборки графа сущностей.
 *
 * <p>Алгоритм рекурсивно обходит {@link RelationGraph} от корневой сущности,
 * для каждой включённой связи добавляет {@code LEFT JOIN} с уникальным алиасом
 * таблицы и формирует псевдонимы колонок вида {@code <prefix>_<column>},
 * где {@code prefix} однозначно идентифицирует путь в графе. Таким образом,
 * результирующий плоский набор строк не содержит коллизий имён колонок,
 * что позволяет {@link ru.diplom.core.mapper.GraphResultSetExtractor}
 * выполнить детерминированную сборку графа объектов.
 */
public class SqlGenerator {

    private final ConcurrentMap<Class<?>, EntityMetadata> metadataCache = new ConcurrentHashMap<>();

    public GeneratedQuery generateSelect(Class<?> rootClass, RelationGraph graph) {
        EntityMetadata rootMetadata = getMetadata(rootClass);
        SelectQueryContext context = new SelectQueryContext(rootMetadata);

        Set<String> consumedPaths = new HashSet<>();

        traverseGraph(rootMetadata, graph, "", 1, context.getRootNode(), context, consumedPaths);

        validateAllPathsConsumed(graph, consumedPaths);

        StringBuilder sql = new StringBuilder("SELECT ");
        appendSelectColumns(sql, context);
        sql.append(" FROM ").append(rootMetadata.getTableName())
                .append(' ').append(context.getRootNode().getTableAlias());
        appendJoins(sql, context);

        return new GeneratedQuery(sql.toString(), context);
    }

    /**
     * Генерирует {@code SELECT ... WHERE <id> = :id} для поиска по первичному
     * ключу. Имя параметра — {@value #ID_PARAMETER}.
     */
    public GeneratedQuery generateSelectById(Class<?> rootClass, RelationGraph graph) {
        GeneratedQuery base = generateSelect(rootClass, graph);
        EntityMetadata rootMetadata = getMetadata(rootClass);
        String rootAlias = base.getContext().getRootNode().getTableAlias();

        String sql = base.getSql() + " WHERE " + rootAlias + '.'
                + rootMetadata.getIdColumnName() + " = :" + ID_PARAMETER;
        return new GeneratedQuery(sql, base.getContext(),
                Map.of(ID_PARAMETER, ID_PLACEHOLDER));
    }

    public static final String ID_PARAMETER = "id";

    /**
     * Маркер вместо реального значения id: связывание выполняется
     * в {@link ru.diplom.core.repository.R2dbcGraphTemplate}, поскольку только
     * там известно фактическое значение. Карта параметров здесь нужна,
     * чтобы исполнитель знал имя плейсхолдера.
     */
    public static final Object ID_PLACEHOLDER = new Object();

    /**
     * Генерирует SELECT с произвольным {@link Query} (фильтр + сортировка + LIMIT/OFFSET).
     *
     * <p>При наличии {@code ONE_TO_MANY} связей и непустого {@code limit} применяется
     * подзапрос: сначала отбираются идентификаторы корневой сущности, затем по ним
     * строится основной запрос — иначе {@code LIMIT} обрезал бы строки декартова
     * произведения, а не сами корневые объекты.
     */
    public GeneratedQuery generateSelectByQuery(Class<?> rootClass, RelationGraph graph, Query query) {
        EntityMetadata rootMetadata = getMetadata(rootClass);
        SelectQueryContext context = new SelectQueryContext(rootMetadata);

        Set<String> consumedPaths = new HashSet<>();
        traverseGraph(rootMetadata, graph, "", 1, context.getRootNode(), context, consumedPaths);
        validateAllPathsConsumed(graph, consumedPaths);

        BindMarkers bindMarkers = new BindMarkers();
        String rootAlias = context.getRootNode().getTableAlias();

        boolean needsSubquery = query.hasLimit() && containsOneToMany(context);

        StringBuilder sql = new StringBuilder("SELECT ");
        appendSelectColumns(sql, context);
        sql.append(" FROM ").append(rootMetadata.getTableName()).append(' ').append(rootAlias);
        appendJoins(sql, context);

        if (needsSubquery) {
            // Подзапрос отбирает корневые id и затем основной запрос фильтрует по ним.
            String subSql = buildIdSubquery(rootMetadata, query, bindMarkers);
            sql.append(" WHERE ").append(rootAlias).append('.').append(rootMetadata.getIdColumnName())
                    .append(" IN (").append(subSql).append(')');
            appendOrderBy(sql, rootMetadata, rootAlias, query.getSort());
        } else {
            if (query.hasCriteria()) {
                sql.append(" WHERE ");
                appendCriteria(sql, query.getCriteria(), rootMetadata, rootAlias, bindMarkers);
            }
            appendOrderBy(sql, rootMetadata, rootAlias, query.getSort());
            appendPagination(sql, query);
        }

        return new GeneratedQuery(sql.toString(), context, bindMarkers.getBindings());
    }

    /**
     * Генерирует {@code SELECT COUNT(*) FROM <table> [WHERE ...]} для
     * подсчёта корневых сущностей по тому же критерию (без JOIN-ов).
     */
    public GeneratedQuery generateCount(Class<?> rootClass, Query query) {
        EntityMetadata rootMetadata = getMetadata(rootClass);
        SelectQueryContext context = new SelectQueryContext(rootMetadata);
        BindMarkers bindMarkers = new BindMarkers();
        String rootAlias = context.getRootNode().getTableAlias();

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM ")
                .append(rootMetadata.getTableName()).append(' ').append(rootAlias);
        if (query.hasCriteria()) {
            sql.append(" WHERE ");
            appendCriteria(sql, query.getCriteria(), rootMetadata, rootAlias, bindMarkers);
        }
        return new GeneratedQuery(sql.toString(), context, bindMarkers.getBindings());
    }

    public EntityMetadata getMetadata(Class<?> clazz) {
        return metadataCache.computeIfAbsent(clazz, MetadataParser::parse);
    }

    /**
     * Рекурсивный обход {@link RelationGraph}. Завершение гарантируется
     * ограничением {@link RelationGraph#getMaxDepth()} — это же ограничение
     * является единственной защитой от бесконечной рекурсии в самоссылающихся
     * сущностях (дерево категорий, иерархия комментариев). Дополнительная
     * проверка «тип уже встречался на пути» не нужна и только ломала бы
     * легитимные сценарии.
     */
    private void traverseGraph(EntityMetadata currentMetadata,
                               RelationGraph graph,
                               String currentPath,
                               int currentDepth,
                               JoinNode parentNode,
                               SelectQueryContext context,
                               Set<String> consumedPaths) {
        if (currentDepth > graph.getMaxDepth()) {
            return;
        }

        for (Map.Entry<String, RelationMetadata> entry : currentMetadata.getRelations().entrySet()) {
            String fieldName = entry.getKey();
            RelationMetadata relation = entry.getValue();

            String relationPath = currentPath.isEmpty() ? fieldName : currentPath + '.' + fieldName;
            if (!graph.includes(relationPath)) {
                continue;
            }
            consumedPaths.add(relationPath);

            EntityMetadata targetMetadata = getMetadata(relation.getTargetType());
            JoinNode targetNode = context.addJoin(targetMetadata, relationPath, parentNode, relation);

            traverseGraph(targetMetadata, graph, relationPath, currentDepth + 1,
                    targetNode, context, consumedPaths);
        }
    }

    /**
     * Проверяет, что каждый явно указанный в {@link RelationGraph#getIncludePaths()}
     * путь действительно соответствует существующей связи и был учтён при обходе.
     * Без этой проверки опечатка вида {@code .include("itemz")} молча игнорировалась бы.
     */
    private void validateAllPathsConsumed(RelationGraph graph, Set<String> consumedPaths) {
        for (String requested : graph.getIncludePaths()) {
            if (consumedPaths.contains(requested)) {
                continue;
            }
            // Путь мог быть отрезан depth — это легитимно, проверяем глубину пути.
            int depthOfPath = 1;
            for (int i = 0; i < requested.length(); i++) {
                if (requested.charAt(i) == '.') {
                    depthOfPath++;
                }
            }
            if (depthOfPath > graph.getMaxDepth()) {
                continue;
            }
            throw new IllegalArgumentException(
                    "Relation path '" + requested + "' does not match any @R2dbcRelation field "
                            + "reachable within depth=" + graph.getMaxDepth());
        }
    }

    private void appendSelectColumns(StringBuilder sql, SelectQueryContext context) {
        boolean first = true;
        for (JoinNode node : context.getNodes()) {
            String alias = node.getTableAlias();
            String prefix = node.getColumnPrefix();
            for (ColumnMetadata column : node.getMetadata().getColumns()) {
                if (!first) {
                    sql.append(", ");
                }
                sql.append(alias).append('.').append(column.getColumnName())
                        .append(" AS ").append(prefix).append('_').append(column.getColumnName());
                first = false;
            }
        }
    }

    private void appendJoins(StringBuilder sql, SelectQueryContext context) {
        for (int i = 1; i < context.getNodes().size(); i++) {
            JoinNode node = context.getNodes().get(i);
            JoinNode parentNode = node.getParent();
            RelationMetadata relation = node.getRelationFromParent();

            sql.append(" LEFT JOIN ").append(node.getMetadata().getTableName())
                    .append(' ').append(node.getTableAlias())
                    .append(" ON ");

            switch (relation.getRelationType()) {
                case MANY_TO_ONE, ONE_TO_ONE -> appendOwningJoin(sql, parentNode, node, relation);
                case ONE_TO_MANY -> appendInverseJoin(sql, parentNode, node, relation);
            }
        }
    }

    /**
     * {@code MANY_TO_ONE}/{@code ONE_TO_ONE}: внешний ключ хранится в родительской таблице.
     * Условие: {@code child.id = parent.<joinColumn>}.
     */
    private void appendOwningJoin(StringBuilder sql, JoinNode parent, JoinNode child, RelationMetadata relation) {
        String fkField = relation.getJoinColumn();
        if (fkField == null || fkField.isEmpty()) {
            throw new IllegalStateException(
                    "Relation " + relation.getFieldName() + " of type " + relation.getRelationType()
                            + " requires non-empty joinColumn");
        }
        sql.append(child.getTableAlias()).append('.').append(child.getMetadata().getIdColumnName())
                .append(" = ")
                .append(parent.getTableAlias()).append('.').append(fkField);
    }

    /**
     * {@code ONE_TO_MANY}: внешний ключ хранится в дочерней таблице.
     * Имя колонки берётся из метаданных дочерней сущности по полю,
     * указанному в {@code mappedBy}.
     */
    private void appendInverseJoin(StringBuilder sql, JoinNode parent, JoinNode child, RelationMetadata relation) {
        String mappedBy = relation.getMappedBy();
        if (mappedBy == null || mappedBy.isEmpty()) {
            throw new IllegalStateException(
                    "Relation " + relation.getFieldName() + " of type ONE_TO_MANY requires non-empty mappedBy");
        }
        ColumnMetadata fkColumn = child.getMetadata().getColumnByFieldName(mappedBy);
        if (fkColumn == null) {
            throw new IllegalStateException(
                    "mappedBy '" + mappedBy + "' does not refer to a known field of "
                            + child.getMetadata().getEntityType().getName());
        }
        sql.append(child.getTableAlias()).append('.').append(fkColumn.getColumnName())
                .append(" = ")
                .append(parent.getTableAlias()).append('.').append(parent.getMetadata().getIdColumnName());
    }

    private boolean containsOneToMany(SelectQueryContext context) {
        for (int i = 1; i < context.getNodes().size(); i++) {
            JoinNode node = context.getNodes().get(i);
            if (node.getRelationFromParent().getRelationType()
                    == ru.diplom.core.metadata.RelationType.ONE_TO_MANY) {
                return true;
            }
        }
        return false;
    }

    private String buildIdSubquery(EntityMetadata rootMetadata, Query query, BindMarkers bindMarkers) {
        StringBuilder sub = new StringBuilder("SELECT ").append(rootMetadata.getIdColumnName())
                .append(" FROM ").append(rootMetadata.getTableName());
        if (query.hasCriteria()) {
            sub.append(" WHERE ");
            appendCriteria(sub, query.getCriteria(), rootMetadata, null, bindMarkers);
        }
        appendOrderBy(sub, rootMetadata, null, query.getSort());
        appendPagination(sub, query);
        return sub.toString();
    }

    private void appendOrderBy(StringBuilder sql, EntityMetadata rootMetadata, String alias, Sort sort) {
        if (!sort.isSorted()) {
            return;
        }
        sql.append(" ORDER BY ");
        for (int i = 0; i < sort.getOrders().size(); i++) {
            Sort.Order order = sort.getOrders().get(i);
            if (i > 0) {
                sql.append(", ");
            }
            ColumnMetadata column = requireColumn(rootMetadata, order.getFieldName());
            if (alias != null) {
                sql.append(alias).append('.');
            }
            sql.append(column.getColumnName()).append(' ').append(order.getDirection().name());
        }
    }

    private void appendPagination(StringBuilder sql, Query query) {
        if (query.hasLimit()) {
            sql.append(" LIMIT ").append(query.getLimit());
        }
        if (query.hasOffset()) {
            sql.append(" OFFSET ").append(query.getOffset());
        }
    }

    private void appendCriteria(StringBuilder sql,
                                Criteria criteria,
                                EntityMetadata rootMetadata,
                                String alias,
                                BindMarkers bindMarkers) {
        criteria.accept(new Criteria.Visitor<Void>() {
            @Override
            public Void visit(Criteria.Comparison comparison) {
                ColumnMetadata column = requireColumn(rootMetadata, comparison.getFieldName());
                if (alias != null) {
                    sql.append(alias).append('.');
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
                    Iterator<?> it = values.iterator();
                    boolean first = true;
                    while (it.hasNext()) {
                        if (!first) {
                            sql.append(", ");
                        }
                        sql.append(':').append(bindMarkers.next(it.next()));
                        first = false;
                    }
                    sql.append(')');
                    return null;
                }

                sql.append(' ').append(op.sql()).append(" :")
                        .append(bindMarkers.next(comparison.getValue()));
                return null;
            }

            @Override
            public Void visit(Criteria.Composite composite) {
                String separator = " " + composite.getOperator().name() + " ";
                sql.append('(');
                for (int i = 0; i < composite.getParts().size(); i++) {
                    if (i > 0) {
                        sql.append(separator);
                    }
                    composite.getParts().get(i).accept(this);
                }
                sql.append(')');
                return null;
            }
        });
    }

    private ColumnMetadata requireColumn(EntityMetadata metadata, String fieldName) {
        ColumnMetadata column = metadata.getColumnByFieldName(fieldName);
        if (column == null) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' is not a known column of "
                            + metadata.getEntityType().getName());
        }
        return column;
    }
}
