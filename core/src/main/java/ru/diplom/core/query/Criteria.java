package ru.diplom.core.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Минималистичное иммутабельное описание условия {@code WHERE} над колонками
 * корневой таблицы запроса. Не пытается воспроизвести {@code Spring Data Criteria}
 * целиком: цель — продемонстрировать в ВКР, что декларативное API расширяется
 * фильтрацией без потери преимуществ модуля.
 *
 * <p>Поддерживаются простые предикаты ({@code =, <>, <, <=, >, >=, IN, IS NULL,
 * IS NOT NULL}) и составные ({@code AND}, {@code OR}).
 *
 * <p>Поля задаются именем Java-поля (например, {@code "customerId"}) — реальное
 * имя колонки разрешается через {@code EntityMetadata}.
 */
public abstract class Criteria {

    Criteria() {
    }

    public static Predicate where(String fieldName) {
        return new Predicate(fieldName);
    }

    public Criteria and(Criteria other) {
        return new Composite(LogicalOperator.AND, List.of(this, other));
    }

    public Criteria or(Criteria other) {
        return new Composite(LogicalOperator.OR, List.of(this, other));
    }

    public abstract <T> T accept(Visitor<T> visitor);

    public enum LogicalOperator {
        AND, OR
    }

    public enum ComparisonOperator {
        EQ("="),
        NEQ("<>"),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        IN("IN"),
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL");

        private final String sql;

        ComparisonOperator(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    /**
     * Visitor для рендеринга криетерии в SQL — позволяет держать рендер
     * отдельно от структуры выражения (см. {@code SqlGenerator}).
     */
    public interface Visitor<T> {
        T visit(Comparison comparison);

        T visit(Composite composite);
    }

    /** Комбинатор предикатов {@code AND}/{@code OR}. */
    public static final class Composite extends Criteria {
        private final LogicalOperator operator;
        private final List<Criteria> parts;

        public Composite(LogicalOperator operator, List<Criteria> parts) {
            this.operator = operator;
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        }

        public LogicalOperator getOperator() {
            return operator;
        }

        public List<Criteria> getParts() {
            return parts;
        }

        @Override
        public <T> T accept(Visitor<T> visitor) {
            return visitor.visit(this);
        }
    }

    /** Атомарное сравнение {@code <field> <op> <value>}. */
    public static final class Comparison extends Criteria {
        private final String fieldName;
        private final ComparisonOperator operator;
        private final Object value;

        public Comparison(String fieldName, ComparisonOperator operator, Object value) {
            this.fieldName = fieldName;
            this.operator = operator;
            this.value = value;
        }

        public String getFieldName() {
            return fieldName;
        }

        public ComparisonOperator getOperator() {
            return operator;
        }

        public Object getValue() {
            return value;
        }

        @Override
        public <T> T accept(Visitor<T> visitor) {
            return visitor.visit(this);
        }
    }

    /** Билдер для одного предиката. */
    public static final class Predicate {
        private final String fieldName;

        Predicate(String fieldName) {
            this.fieldName = fieldName;
        }

        /**
         * Равенство со значением. Если {@code value == null}, автоматически
         * превращается в {@code IS NULL} — напрямую сравнивать колонку с
         * {@code NULL} в SQL нельзя, результат всегда {@code UNKNOWN}.
         */
        public Criteria is(Object value) {
            if (value == null) {
                return isNull();
            }
            return new Comparison(fieldName, ComparisonOperator.EQ, value);
        }

        /** См. {@link #is(Object)}: {@code null} превращается в {@code IS NOT NULL}. */
        public Criteria not(Object value) {
            if (value == null) {
                return isNotNull();
            }
            return new Comparison(fieldName, ComparisonOperator.NEQ, value);
        }

        public Criteria lessThan(Object value) {
            return new Comparison(fieldName, ComparisonOperator.LT, value);
        }

        public Criteria lessThanOrEquals(Object value) {
            return new Comparison(fieldName, ComparisonOperator.LTE, value);
        }

        public Criteria greaterThan(Object value) {
            return new Comparison(fieldName, ComparisonOperator.GT, value);
        }

        public Criteria greaterThanOrEquals(Object value) {
            return new Comparison(fieldName, ComparisonOperator.GTE, value);
        }

        public Criteria in(Collection<?> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("IN-criteria requires non-empty values");
            }
            return new Comparison(fieldName, ComparisonOperator.IN, List.copyOf(values));
        }

        public Criteria isNull() {
            return new Comparison(fieldName, ComparisonOperator.IS_NULL, null);
        }

        public Criteria isNotNull() {
            return new Comparison(fieldName, ComparisonOperator.IS_NOT_NULL, null);
        }
    }
}
