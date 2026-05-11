package ru.diplom.core.mapper;

import org.junit.jupiter.api.Test;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.sql.SelectQueryContext;
import ru.diplom.core.sql.SqlGenerator;
import ru.diplom.core.testfixtures.Author;
import ru.diplom.core.testfixtures.Book;
import ru.diplom.core.testfixtures.Publisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphResultSetExtractorTest {

    private final SqlGenerator generator = new SqlGenerator();
    private final GraphResultSetExtractor extractor = new GraphResultSetExtractor();

    @Test
    void extractsSingleRootWithoutRelations() {
        SelectQueryContext context = generator.generateSelect(Author.class, RelationGraph.empty()).getContext();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("root_id", 1L);
        row.put("root_name", "Tolstoy");
        row.put("root_country_code", "RU");

        List<Author> result = extractor.extractData(List.of(row), context);

        assertThat(result).hasSize(1);
        Author a = result.get(0);
        assertThat(a.getId()).isEqualTo(1L);
        assertThat(a.getName()).isEqualTo("Tolstoy");
        assertThat(a.getCountryCode()).isEqualTo("RU");
        assertThat(a.getBooks()).isEmpty();
    }

    @Test
    void deduplicatesRootAcrossCartesianProduct() {
        RelationGraph graph = RelationGraph.builder().depth(1).include("books").build();
        SelectQueryContext context = generator.generateSelect(Author.class, graph).getContext();

        Map<String, Object> r1 = baseRow();
        r1.put("books_id", 10L);
        r1.put("books_title", "War and Peace");
        r1.put("books_author_id", 1L);

        Map<String, Object> r2 = baseRow();
        r2.put("books_id", 11L);
        r2.put("books_title", "Anna Karenina");
        r2.put("books_author_id", 1L);

        List<Author> result = extractor.extractData(List.of(r1, r2), context);

        assertThat(result).hasSize(1);
        Author a = result.get(0);
        assertThat(a.getBooks()).extracting(Book::getTitle)
                .containsExactly("War and Peace", "Anna Karenina");
    }

    @Test
    void handlesNullsFromLeftJoin() {
        RelationGraph graph = RelationGraph.builder().depth(1).include("books").build();
        SelectQueryContext context = generator.generateSelect(Author.class, graph).getContext();

        Map<String, Object> row = baseRow();
        row.put("books_id", null);
        row.put("books_title", null);
        row.put("books_author_id", null);

        List<Author> result = extractor.extractData(List.of(row), context);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBooks()).isEmpty();
    }

    @Test
    void buildsNestedRelations() {
        RelationGraph graph = RelationGraph.builder()
                .depth(2)
                .include("books")
                .include("books.publisher")
                .build();
        SelectQueryContext context = generator.generateSelect(Author.class, graph).getContext();

        Map<String, Object> row = baseRow();
        row.put("books_id", 10L);
        row.put("books_title", "Hamlet");
        row.put("books_author_id", 1L);
        row.put("books_publisher_id", 100L);
        row.put("books__publisher_id", 100L);
        row.put("books__publisher_name", "Penguin");

        List<Author> result = extractor.extractData(List.of(row), context);
        assertThat(result).hasSize(1);
        Book book = result.get(0).getBooks().get(0);
        assertThat(book.getPublisher()).isNotNull();
        assertThat(book.getPublisher().getName()).isEqualTo("Penguin");
    }

    @Test
    void emptyInputReturnsEmptyList() {
        SelectQueryContext context = generator.generateSelect(Author.class, RelationGraph.empty()).getContext();
        assertThat(extractor.<Author>extractData(List.of(), context)).isEmpty();
        assertThat(extractor.<Author>extractData(null, context)).isEmpty();
    }

    @Test
    void publisherSampleIsUsedToAvoidUnusedImport() {
        // тест-фикстура Publisher уже задействована в buildsNestedRelations;
        // это проверка, что класс не удалён случайно при рефакторинге.
        assertThat(new Publisher().getId()).isNull();
    }

    private static Map<String, Object> baseRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("root_id", 1L);
        row.put("root_name", "Tolstoy");
        row.put("root_country_code", "RU");
        return row;
    }
}
