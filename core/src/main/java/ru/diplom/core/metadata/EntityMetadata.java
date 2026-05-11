package ru.diplom.core.metadata;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * Метаданные о сущности, извлечённые на основе аннотаций Spring Data
 * ({@code @Table}, {@code @Id}, {@code @Column}) и кастомной аннотации
 * {@link R2dbcRelation}. Кэшируется в {@link ru.diplom.core.sql.SqlGenerator}.
 */
@Getter
@Builder
@ToString
public class EntityMetadata {

    private final Class<?> entityType;

    private final String tableName;

    /** Метаданные первичного ключа. Дублируется в {@link #columns} для удобства. */
    private final ColumnMetadata idColumn;

    /** Все столбцы-свойства сущности (включая первичный ключ), кроме связей. */
    private final List<ColumnMetadata> columns;

    /** Быстрый доступ к колонке по имени Java-поля. */
    private final Map<String, ColumnMetadata> columnsByFieldName;

    /** Метаданные связей с другими сущностями. Ключ — имя поля. */
    private final Map<String, RelationMetadata> relations;

    public RelationMetadata getRelation(String fieldName) {
        return relations.get(fieldName);
    }

    public ColumnMetadata getColumnByFieldName(String fieldName) {
        return columnsByFieldName.get(fieldName);
    }

    public String getIdColumnName() {
        return idColumn.getColumnName();
    }

    public String getIdFieldName() {
        return idColumn.getFieldName();
    }
}
