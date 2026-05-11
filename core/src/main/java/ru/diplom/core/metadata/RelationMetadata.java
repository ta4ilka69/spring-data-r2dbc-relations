package ru.diplom.core.metadata;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.lang.reflect.Field;

/**
 * Метаданные о конкретной связи между сущностями.
 */
@Getter
@Builder
@ToString
public class RelationMetadata {
    
    /**
     * Поле класса (объекта), в котором объявлена связь (например, List<OrderItem> items).
     */
    private final Field field;

    /**
     * Имя поля в классе.
     */
    private final String fieldName;

    /**
     * Тип сущности, на которую указывает связь. 
     * Если это коллекция (List<Item>), то targetType = Item.class.
     */
    private final Class<?> targetType;

    /**
     * Тип отношения.
     */
    private final RelationType relationType;

    /**
     * Имя столбца для JOIN-а.
     */
    private final String joinColumn;

    /**
     * Имя свойства в связанной сущности, если связь двунаправленная.
     */
    private final String mappedBy;
    
    /**
     * Является ли поле коллекцией (например, List или Set).
     */
    private final boolean isCollection;
}
