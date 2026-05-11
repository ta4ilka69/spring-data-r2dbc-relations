package ru.diplom.core.graph;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Декларативное описание графа связанных сущностей, которые необходимо
 * загрузить вместе с корневой сущностью.
 *
 * <p>Является аналогом {@code EntityGraph} из JPA, адаптированным под
 * реактивный стек: загрузка всегда явная, ленивые прокси не используются.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class RelationGraph {

    /**
     * Стратегия физической выборки данных.
     *
     * <ul>
     *   <li>{@link #JOIN} — один SQL-запрос с {@code LEFT JOIN}-ами (по умолчанию).
     *       Минимизирует число обращений к БД, но при нескольких {@code ONE_TO_MANY}
     *       коллекциях результирующее множество строк растёт мультипликативно
     *       (декартов взрыв).</li>
     *   <li>{@link #MULTI_QUERY} — отдельный запрос на каждую коллекцию.
     *       Передаёт меньше строк (нет дублирования родительских колонок),
     *       но требует N+1 round-trip к базе. Полезна при широких корнях
     *       и нескольких больших коллекциях.</li>
     * </ul>
     *
     * <p>Выбор стратегии — практический trade-off, явно упомянутый в задании ВКР.
     */
    public enum FetchStrategy {
        JOIN,
        MULTI_QUERY
    }

    /**
     * Максимальная глубина загрузки. Защищает от циклических зависимостей
     * и от чрезмерного декартова произведения при выборке коллекций.
     */
    private final int maxDepth;

    /**
     * Пути включённых связей в точечной нотации (например, {@code items.product}).
     */
    private final Set<String> includePaths;

    private final FetchStrategy fetchStrategy;

    public static Builder builder() {
        return new Builder();
    }

    /** Пустой граф (загружается только корневая сущность). */
    public static RelationGraph empty() {
        return builder().build();
    }

    public boolean includes(String path) {
        return includePaths.contains(path);
    }

    public static class Builder {
        private int depth = 1;
        private final Set<String> paths = new LinkedHashSet<>();
        private FetchStrategy strategy = FetchStrategy.JOIN;

        /**
         * Устанавливает максимальную глубину загрузки. По умолчанию — 1.
         */
        public Builder depth(int maxDepth) {
            if (maxDepth < 0) {
                throw new IllegalArgumentException("Depth must be >= 0");
            }
            this.depth = maxDepth;
            return this;
        }

        /**
         * Добавляет путь связи к загрузке. Точечная нотация описывает вложенность,
         * например {@code items.product}.
         */
        public Builder include(String relationPath) {
            if (relationPath != null && !relationPath.isBlank()) {
                this.paths.add(relationPath.trim());
            }
            return this;
        }

        public Builder fetchStrategy(FetchStrategy strategy) {
            if (strategy != null) {
                this.strategy = strategy;
            }
            return this;
        }

        public RelationGraph build() {
            return new RelationGraph(depth,
                    Collections.unmodifiableSet(new LinkedHashSet<>(paths)),
                    strategy);
        }
    }
}
