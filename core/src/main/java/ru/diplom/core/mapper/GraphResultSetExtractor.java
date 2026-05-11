package ru.diplom.core.mapper;

import org.springframework.beans.BeanUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import ru.diplom.core.metadata.ColumnMetadata;
import ru.diplom.core.metadata.EntityMetadata;
import ru.diplom.core.metadata.RelationMetadata;
import ru.diplom.core.sql.JoinNode;
import ru.diplom.core.sql.SelectQueryContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сборщик графа объектов из плоских строк, возвращаемых {@code DatabaseClient}.
 *
 * <p>Алгоритм работает за O(N · M), где N — количество строк, M — количество
 * узлов в графе. Дедупликация выполняется через кэш {@code path -> id -> instance};
 * это гарантирует ссылочную целостность графа в рамках одной сборки и
 * корректную обработку декартова произведения коллекций.
 */
public class GraphResultSetExtractor {

    private final ConversionService conversionService = DefaultConversionService.getSharedInstance();

    public <T> List<T> extractData(List<Map<String, Object>> rows, SelectQueryContext context) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        JoinNode rootNode = context.getRootNode();

        Map<String, Map<Object, Object>> instanceCache = new HashMap<>();
        for (JoinNode node : context.getNodes()) {
            instanceCache.put(node.getPath(), new LinkedHashMap<>());
        }

        for (Map<String, Object> row : rows) {
            Object rootId = extractId(row, rootNode);
            if (rootId == null) {
                continue;
            }
            instanceCache.get(rootNode.getPath())
                    .computeIfAbsent(rootId, id -> instantiateAndMap(row, rootNode));

            for (int i = 1; i < context.getNodes().size(); i++) {
                JoinNode node = context.getNodes().get(i);
                JoinNode parentNode = node.getParent();

                Object parentId = extractId(row, parentNode);
                Object childId = extractId(row, node);
                if (parentId == null || childId == null) {
                    continue;
                }

                Object parentInstance = instanceCache.get(parentNode.getPath()).get(parentId);
                if (parentInstance == null) {
                    continue;
                }

                Object childInstance = instanceCache.get(node.getPath())
                        .computeIfAbsent(childId, id -> instantiateAndMap(row, node));

                linkEntities(parentInstance, childInstance, node.getRelationFromParent());
            }
        }

        @SuppressWarnings("unchecked")
        List<T> result = new ArrayList<>((Collection<T>) instanceCache.get(rootNode.getPath()).values());
        return result;
    }

    private Object extractId(Map<String, Object> row, JoinNode node) {
        String label = labelFor(node, node.getMetadata().getIdColumnName());
        return row.get(label);
    }

    private Object instantiateAndMap(Map<String, Object> row, JoinNode node) {
        EntityMetadata meta = node.getMetadata();
        Object instance = BeanUtils.instantiateClass(meta.getEntityType());

        for (ColumnMetadata column : meta.getColumns()) {
            String label = labelFor(node, column.getColumnName());
            Object value = row.get(label);
            if (value == null) {
                continue;
            }
            Field field = column.getField();
            try {
                if (!field.getType().isInstance(value)
                        && conversionService.canConvert(value.getClass(), field.getType())) {
                    value = conversionService.convert(value, field.getType());
                }
                field.set(instance, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Could not set value for field " + field.getName(), e);
            }
        }

        // Заранее инициализируем коллекции связей, чтобы вызов linkEntities
        // мог безопасно дописывать в них элементы и чтобы клиент не получал null
        // даже если в графе нет соответствующих JOIN-узлов.
        for (RelationMetadata relation : meta.getRelations().values()) {
            if (!relation.isCollection()) {
                continue;
            }
            Field field = relation.getField();
            try {
                if (Set.class.isAssignableFrom(field.getType())) {
                    field.set(instance, new HashSet<>());
                } else {
                    field.set(instance, new ArrayList<>());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Failed to initialize collection for field " + field.getName(), e);
            }
        }

        return instance;
    }

    @SuppressWarnings("unchecked")
    private void linkEntities(Object parent, Object child, RelationMetadata relation) {
        Field field = relation.getField();
        try {
            if (relation.isCollection()) {
                Collection<Object> collection = (Collection<Object>) field.get(parent);
                if (collection == null) {
                    collection = new ArrayList<>();
                    field.set(parent, collection);
                }
                if (!collection.contains(child)) {
                    collection.add(child);
                }
            } else {
                field.set(parent, child);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Could not link entities via field " + relation.getFieldName(), e);
        }
    }

    private static String labelFor(JoinNode node, String columnName) {
        return node.getColumnPrefix() + '_' + columnName;
    }
}
