package ru.diplom.core.metadata;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.lang.reflect.Field;

/**
 * Метаданные о столбце-свойстве сущности (поле, не описывающее связь).
 * Используется генератором SQL для построения списка SELECT и
 * {@link ru.diplom.core.mapper.GraphResultSetExtractor} для записи значений.
 */
@Getter
@ToString
@RequiredArgsConstructor
public class ColumnMetadata {

    private final Field field;
    private final String fieldName;
    private final String columnName;
    private final boolean idColumn;
}
