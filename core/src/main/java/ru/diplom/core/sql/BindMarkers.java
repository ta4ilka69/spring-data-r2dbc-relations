package ru.diplom.core.sql;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Простая фабрика именованных bind-маркеров вида {@code :p0, :p1, ...}.
 * Накапливает значения параметров в порядке регистрации; используется
 * при рендере {@link ru.diplom.core.query.Criteria} в SQL и при последующем
 * связывании в {@link ru.diplom.core.repository.R2dbcGraphTemplate}.
 */
public class BindMarkers {

    private final String prefix;
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    private int counter = 0;

    public BindMarkers() {
        this("p");
    }

    public BindMarkers(String prefix) {
        this.prefix = prefix;
    }

    /** Регистрирует значение и возвращает имя bind-параметра без двоеточия. */
    public String next(Object value) {
        String name = prefix + counter++;
        bindings.put(name, value);
        return name;
    }

    public Map<String, Object> getBindings() {
        return bindings;
    }
}
