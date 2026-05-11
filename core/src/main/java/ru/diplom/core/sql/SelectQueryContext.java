package ru.diplom.core.sql;

import lombok.Getter;
import ru.diplom.core.metadata.EntityMetadata;
import ru.diplom.core.metadata.RelationMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Контекст генерации SQL-запроса. Хранит участвующие в выборке узлы
 * (таблицы) и используется маппером для сборки результата.
 */
@Getter
public class SelectQueryContext {

    private static final String ROOT_PREFIX = "root";

    private final EntityMetadata rootMetadata;

    private int tableAliasCounter = 0;

    private final List<JoinNode> nodes = new ArrayList<>();

    private final Map<String, JoinNode> nodeByPath = new HashMap<>();

    public SelectQueryContext(EntityMetadata rootMetadata) {
        this.rootMetadata = rootMetadata;

        JoinNode rootNode = new JoinNode(rootMetadata, generateTableAlias(), "", ROOT_PREFIX);
        nodes.add(rootNode);
        nodeByPath.put("", rootNode);
    }

    /**
     * Регистрирует узел JOIN. Префикс колонок строится из пути графа:
     * точки заменяются на {@code __}, например {@code items.product → items__product}.
     */
    public JoinNode addJoin(EntityMetadata metadata, String path, JoinNode parent, RelationMetadata relation) {
        String tableAlias = generateTableAlias();
        String columnPrefix = path.replace(".", "__");

        JoinNode node = new JoinNode(metadata, tableAlias, path, columnPrefix, parent, relation);
        nodes.add(node);
        nodeByPath.put(path, node);
        return node;
    }

    public JoinNode getRootNode() {
        return nodes.get(0);
    }

    private String generateTableAlias() {
        return "t" + (tableAliasCounter++);
    }
}
