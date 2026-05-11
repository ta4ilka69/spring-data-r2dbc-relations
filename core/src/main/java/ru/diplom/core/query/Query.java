package ru.diplom.core.query;

/**
 * Иммутабельный запрос: критерий, сортировка, постраничные параметры.
 *
 * <p>Поля сортировки и условия фильтрации применяются только к корневой
 * таблице запроса. Постраничная выборка для запросов с {@code ONE_TO_MANY}
 * связями реализуется как {@code WHERE root.id IN (SELECT ... LIMIT N OFFSET M)},
 * чтобы {@code LIMIT} обрезал корневые объекты, а не строки декартова произведения.
 */
public final class Query {

    private static final Query EMPTY = new Query(null, Sort.unsorted(), -1, 0);

    private final Criteria criteria;
    private final Sort sort;
    private final int limit;
    private final long offset;

    private Query(Criteria criteria, Sort sort, int limit, long offset) {
        this.criteria = criteria;
        this.sort = sort != null ? sort : Sort.unsorted();
        this.limit = limit;
        this.offset = Math.max(0, offset);
    }

    public static Query empty() {
        return EMPTY;
    }

    public static Query query(Criteria criteria) {
        return new Query(criteria, Sort.unsorted(), -1, 0);
    }

    public Query with(Criteria criteria) {
        return new Query(criteria, sort, limit, offset);
    }

    public Query sort(Sort sort) {
        return new Query(criteria, sort, limit, offset);
    }

    public Query limit(int limit) {
        return new Query(criteria, sort, limit, offset);
    }

    public Query offset(long offset) {
        return new Query(criteria, sort, limit, offset);
    }

    public Criteria getCriteria() {
        return criteria;
    }

    public Sort getSort() {
        return sort;
    }

    public int getLimit() {
        return limit;
    }

    public long getOffset() {
        return offset;
    }

    public boolean hasLimit() {
        return limit > 0;
    }

    public boolean hasOffset() {
        return offset > 0;
    }

    public boolean hasCriteria() {
        return criteria != null;
    }
}
