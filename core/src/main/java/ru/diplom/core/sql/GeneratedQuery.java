package ru.diplom.core.sql;

import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * Результат генерации SQL: текст запроса, контекст для маппинга и список
 * именованных bind-параметров (для {@code WHERE id = :id}, {@code IN (:p0, :p1, ...)} и т.п.).
 */
@Getter
public class GeneratedQuery {
    private final String sql;
    private final SelectQueryContext context;
    private final Map<String, Object> bindings;

    public GeneratedQuery(String sql, SelectQueryContext context) {
        this(sql, context, Collections.emptyMap());
    }

    public GeneratedQuery(String sql, SelectQueryContext context, Map<String, Object> bindings) {
        this.sql = sql;
        this.context = context;
        this.bindings = bindings == null ? Collections.emptyMap() : bindings;
    }
}
