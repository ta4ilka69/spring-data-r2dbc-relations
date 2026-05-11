package ru.diplom.core.sql;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import ru.diplom.core.metadata.EntityMetadata;
import ru.diplom.core.metadata.RelationMetadata;

/**
 * Узел дерева JOIN-ов для одной таблицы запроса.
 *
 * <p>Алиас таблицы и префикс колонок гарантируют отсутствие коллизий
 * имён в результирующем плоском наборе строк, даже если одна и та же
 * таблица участвует в запросе несколько раз (например, {@code manager}
 * и {@code employee}, оба ссылающиеся на {@code users}).
 */
@Getter
@RequiredArgsConstructor
public class JoinNode {

    private final EntityMetadata metadata;

    /** Алиас таблицы в SQL ({@code t0}, {@code t1}, ...). */
    private final String tableAlias;

    /** Путь от корня в точечной нотации (например, {@code items.product}). */
    private final String path;

    /** Префикс колонок ({@code root}, {@code items}, {@code items__product}). */
    private final String columnPrefix;

    /** Родительский узел; {@code null} только для корня. */
    private final JoinNode parent;

    /** Связь, по которой узел был добавлен в дерево; {@code null} для корня. */
    private final RelationMetadata relationFromParent;

    public JoinNode(EntityMetadata metadata, String tableAlias, String path, String columnPrefix) {
        this(metadata, tableAlias, path, columnPrefix, null, null);
    }
}
