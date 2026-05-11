package ru.diplom.core.sql;

import org.junit.jupiter.api.Test;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.query.Criteria;
import ru.diplom.core.query.Query;
import ru.diplom.core.query.Sort;
import ru.diplom.core.testfixtures.Author;
import ru.diplom.core.testfixtures.Book;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGeneratorTest {

    private final SqlGenerator generator = new SqlGenerator();

    @Test
    void generatesFlatSelectWhenGraphIsEmpty() {
        GeneratedQuery q = generator.generateSelect(Author.class, RelationGraph.empty());
        assertThat(q.getSql()).isEqualTo(
                "SELECT t0.id AS root_id, t0.name AS root_name, t0.country_code AS root_country_code"
                        + " FROM authors t0");
    }

    @Test
    void generatesOneToManyJoinUsingMappedByColumn() {
        RelationGraph graph = RelationGraph.builder().depth(1).include("books").build();
        GeneratedQuery q = generator.generateSelect(Author.class, graph);
        assertThat(q.getSql())
                .contains("FROM authors t0")
                .contains("LEFT JOIN books t1 ON t1.author_id = t0.id")
                .contains("t1.id AS books_id")
                .contains("t1.title AS books_title");
    }

    @Test
    void generatesNestedManyToOneWithDoubleUnderscorePrefix() {
        RelationGraph graph = RelationGraph.builder()
                .depth(2)
                .include("books")
                .include("books.publisher")
                .build();
        GeneratedQuery q = generator.generateSelect(Author.class, graph);
        assertThat(q.getSql())
                .contains("LEFT JOIN publishers t2 ON t2.id = t1.publisher_id")
                .contains("t2.id AS books__publisher_id")
                .contains("t2.name AS books__publisher_name");
    }

    @Test
    void generateSelectByIdAddsNamedWhere() {
        GeneratedQuery q = generator.generateSelectById(Author.class, RelationGraph.empty());
        assertThat(q.getSql()).endsWith(" WHERE t0.id = :id");
        assertThat(q.getBindings()).containsKey(SqlGenerator.ID_PARAMETER);
    }

    @Test
    void rejectsUnknownIncludePaths() {
        RelationGraph graph = RelationGraph.builder().include("typo").build();
        assertThatThrownBy(() -> generator.generateSelect(Author.class, graph))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typo");
    }

    @Test
    void allowsPathsThatExceedMaxDepth() {
        // Путь books.publisher лежит глубже maxDepth=1, поэтому валидация
        // не должна срабатывать — пользователь сможет сначала задать
        // глубокий include, а реальную глубину менять отдельно.
        RelationGraph graph = RelationGraph.builder()
                .depth(1)
                .include("books")
                .include("books.publisher")
                .build();
        GeneratedQuery q = generator.generateSelect(Author.class, graph);
        assertThat(q.getSql()).doesNotContain("publishers");
    }

    @Test
    void renderCriteriaWithBindMarkers() {
        Query query = Query.query(
                Criteria.where("countryCode").is("RU")
                        .and(Criteria.where("name").isNotNull())
        );
        GeneratedQuery q = generator.generateSelectByQuery(Author.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).contains("WHERE (t0.country_code = :p0 AND t0.name IS NOT NULL)");
        assertThat(q.getBindings()).containsEntry("p0", "RU");
    }

    @Test
    void renderInCriteria() {
        Query query = Query.query(Criteria.where("id").in(List.of(1L, 2L, 3L)));
        GeneratedQuery q = generator.generateSelectByQuery(Author.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).contains("t0.id IN (:p0, :p1, :p2)");
        assertThat(q.getBindings()).containsEntry("p0", 1L)
                .containsEntry("p1", 2L)
                .containsEntry("p2", 3L);
    }

    @Test
    void renderSortAndPaginationWithoutOneToMany() {
        Query query = Query.empty()
                .sort(Sort.by(Sort.desc("name")))
                .limit(10).offset(20);
        GeneratedQuery q = generator.generateSelectByQuery(Author.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).endsWith("ORDER BY t0.name DESC LIMIT 10 OFFSET 20");
    }

    @Test
    void wrapsLimitInIdSubqueryWhenOneToManyJoinPresent() {
        // Без обёртки LIMIT обрезал бы строки декартова произведения.
        RelationGraph graph = RelationGraph.builder().depth(1).include("books").build();
        Query query = Query.query(Criteria.where("countryCode").is("RU")).limit(5);
        GeneratedQuery q = generator.generateSelectByQuery(Author.class, graph, query);

        assertThat(q.getSql())
                .contains("LEFT JOIN books t1")
                .contains("WHERE t0.id IN (SELECT id FROM authors WHERE country_code = :p0 LIMIT 5)");
    }

    @Test
    void nestedNonCyclicGraphGenerates() {
        // Санити-чек: нормальный вложенный граф без рекурсии работает.
        // Защита от бесконечного обхода самоссылающихся сущностей возложена
        // на ограничение depth — см. SqlGeneratorCornerCasesTest.
        RelationGraph graph = RelationGraph.builder()
                .depth(3)
                .include("books")
                .include("books.publisher")
                .build();
        GeneratedQuery q = generator.generateSelect(Author.class, graph);
        assertThat(q.getSql()).contains("publishers");
    }

    @Test
    void countQueryHasNoJoins() {
        GeneratedQuery q = generator.generateCount(Book.class,
                Query.query(Criteria.where("authorId").is(1L)));
        assertThat(q.getSql()).isEqualTo("SELECT COUNT(*) AS total FROM books t0 WHERE t0.author_id = :p0");
        assertThat(q.getBindings()).containsEntry("p0", 1L);
    }
}
