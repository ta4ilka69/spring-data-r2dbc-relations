package ru.diplom.core.metadata;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Парсер метаданных, преобразующий класс-сущность в {@link EntityMetadata}.
 * Учитывает аннотации Spring Data ({@code @Table}, {@code @Id}, {@code @Column})
 * и кастомную {@link R2dbcRelation}.
 */
public final class MetadataParser {

    private MetadataParser() {
    }

    public static EntityMetadata parse(Class<?> entityClass) {
        String tableName = resolveTableName(entityClass);

        Map<String, ColumnMetadata> columns = new LinkedHashMap<>();
        Map<String, RelationMetadata> relations = new LinkedHashMap<>();
        ColumnMetadata idColumn = null;

        // Обход иерархии от самого верхнего родителя к текущему классу,
        // чтобы сохранить порядок «поля родителя → поля наследника» и одновременно
        // позволить наследнику «перекрывать» поле с тем же именем (редкий, но
        // реальный случай — при этом выигрывает самый нижний по иерархии класс).
        Deque<Class<?>> hierarchy = new ArrayDeque<>();
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.push(c);
        }

        for (Class<?> currentClass : hierarchy) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (isStaticOrSynthetic(field)) {
                    continue;
                }
                if (field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                field.setAccessible(true);

                if (field.isAnnotationPresent(R2dbcRelation.class)) {
                    RelationMetadata relation = buildRelation(field);
                    relations.put(field.getName(), relation);
                    // Если наследник перекрыл поле, ранее добавленное как колонка — убираем колонку.
                    columns.remove(field.getName());
                    continue;
                }

                ColumnMetadata column = buildColumn(field);
                columns.put(field.getName(), column);
                // Симметрично: перекрытие колонки бывшей связью.
                relations.remove(field.getName());
                if (column.isIdColumn()) {
                    if (idColumn != null && !idColumn.getFieldName().equals(column.getFieldName())) {
                        throw new IllegalArgumentException(
                                "Entity " + entityClass.getName() + " has multiple @Id fields");
                    }
                    idColumn = column;
                }
            }
        }

        if (idColumn == null) {
            throw new IllegalArgumentException(
                    "Entity " + entityClass.getName() + " must have @Id field");
        }

        return EntityMetadata.builder()
                .entityType(entityClass)
                .tableName(tableName)
                .idColumn(idColumn)
                .columns(java.util.List.copyOf(columns.values()))
                .columnsByFieldName(Collections.unmodifiableMap(columns))
                .relations(Collections.unmodifiableMap(relations))
                .build();
    }

    private static ColumnMetadata buildColumn(Field field) {
        String columnName = resolveColumnName(field);
        boolean isId = field.isAnnotationPresent(Id.class);
        return new ColumnMetadata(field, field.getName(), columnName, isId);
    }

    private static RelationMetadata buildRelation(Field field) {
        R2dbcRelation relationAnn = field.getAnnotation(R2dbcRelation.class);
        boolean isCollection = Collection.class.isAssignableFrom(field.getType());
        Class<?> targetType = resolveTargetType(field);

        if (relationAnn.type() == RelationType.ONE_TO_MANY && !isCollection) {
            throw new IllegalArgumentException(
                    "Field " + field.getDeclaringClass().getName() + "#" + field.getName()
                            + " declared as ONE_TO_MANY must be a Collection; actual type is "
                            + field.getType().getName());
        }
        if ((relationAnn.type() == RelationType.MANY_TO_ONE
                || relationAnn.type() == RelationType.ONE_TO_ONE) && isCollection) {
            throw new IllegalArgumentException(
                    "Field " + field.getDeclaringClass().getName() + "#" + field.getName()
                            + " declared as " + relationAnn.type()
                            + " must NOT be a Collection");
        }

        return RelationMetadata.builder()
                .field(field)
                .fieldName(field.getName())
                .targetType(targetType)
                .relationType(relationAnn.type())
                .joinColumn(relationAnn.joinColumn())
                .mappedBy(relationAnn.mappedBy())
                .isCollection(isCollection)
                .build();
    }

    private static String resolveColumnName(Field field) {
        if (field.isAnnotationPresent(Column.class)) {
            String value = field.getAnnotation(Column.class).value();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return toSnakeCase(field.getName());
    }

    private static String resolveTableName(Class<?> entityClass) {
        if (entityClass.isAnnotationPresent(Table.class)) {
            Table tableAnn = entityClass.getAnnotation(Table.class);
            if (!tableAnn.value().isEmpty()) {
                return tableAnn.value();
            }
            if (!tableAnn.name().isEmpty()) {
                return tableAnn.name();
            }
        }
        return toSnakeCase(entityClass.getSimpleName());
    }

    private static Class<?> resolveTargetType(Field field) {
        if (Collection.class.isAssignableFrom(field.getType())) {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType parameterizedType) {
                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                if (typeArguments.length > 0 && typeArguments[0] instanceof Class<?> cls) {
                    return cls;
                }
            }
            throw new IllegalArgumentException(
                    "Collection field " + field.getName() + " must specify generic type");
        }
        return field.getType();
    }

    private static boolean isStaticOrSynthetic(Field field) {
        return field.isSynthetic()
                || java.lang.reflect.Modifier.isStatic(field.getModifiers());
    }

    static String toSnakeCase(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }
}
