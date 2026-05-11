package ru.diplom.core.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Простое описание порядка сортировки по полям корневой сущности.
 */
public final class Sort {

    private static final Sort UNSORTED = new Sort(Collections.emptyList());

    private final List<Order> orders;

    private Sort(List<Order> orders) {
        this.orders = Collections.unmodifiableList(new ArrayList<>(orders));
    }

    public static Sort unsorted() {
        return UNSORTED;
    }

    public static Sort by(Order... orders) {
        return new Sort(Arrays.asList(orders));
    }

    public static Order asc(String fieldName) {
        return new Order(fieldName, Direction.ASC);
    }

    public static Order desc(String fieldName) {
        return new Order(fieldName, Direction.DESC);
    }

    public List<Order> getOrders() {
        return orders;
    }

    public boolean isSorted() {
        return !orders.isEmpty();
    }

    public enum Direction {
        ASC, DESC
    }

    public static final class Order {
        private final String fieldName;
        private final Direction direction;

        public Order(String fieldName, Direction direction) {
            this.fieldName = fieldName;
            this.direction = direction;
        }

        public String getFieldName() {
            return fieldName;
        }

        public Direction getDirection() {
            return direction;
        }
    }
}
