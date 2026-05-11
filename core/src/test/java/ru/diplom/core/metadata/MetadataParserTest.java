package ru.diplom.core.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.diplom.core.testfixtures.Author;
import ru.diplom.core.testfixtures.Book;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataParserTest {

    @Test
    void parsesTableNameFromAnnotation() {
        EntityMetadata meta = MetadataParser.parse(Author.class);
        assertThat(meta.getTableName()).isEqualTo("authors");
    }

    @Test
    void fallsBackToSnakeCaseClassNameWhenNoTable() {
        EntityMetadata meta = MetadataParser.parse(NoTable.class);
        assertThat(meta.getTableName()).isEqualTo("no_table");
    }

    @Test
    void resolvesIdColumnAndCustomColumnName() {
        EntityMetadata meta = MetadataParser.parse(Author.class);
        assertThat(meta.getIdColumnName()).isEqualTo("id");
        assertThat(meta.getColumnByFieldName("countryCode").getColumnName()).isEqualTo("country_code");
    }

    @Test
    void exposesRelations() {
        EntityMetadata meta = MetadataParser.parse(Author.class);
        RelationMetadata books = meta.getRelation("books");
        assertThat(books).isNotNull();
        assertThat(books.getRelationType()).isEqualTo(RelationType.ONE_TO_MANY);
        assertThat(books.isCollection()).isTrue();
        assertThat(books.getTargetType()).isEqualTo(Book.class);
    }

    @Test
    void exposesColumnsExcludingRelations() {
        EntityMetadata meta = MetadataParser.parse(Author.class);
        List<String> fieldNames = meta.getColumns().stream()
                .map(ColumnMetadata::getFieldName)
                .toList();
        assertThat(fieldNames).containsExactlyInAnyOrder("id", "name", "countryCode");
    }

    @Test
    void rejectsClassesWithoutId() {
        assertThatThrownBy(() -> MetadataParser.parse(NoIdEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Id");
    }

    @Test
    void rejectsClassesWithMultipleIds() {
        assertThatThrownBy(() -> MetadataParser.parse(TwoIdEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple");
    }

    @Test
    void ignoresStaticFields() {
        EntityMetadata meta = MetadataParser.parse(WithStatic.class);
        assertThat(meta.getColumns())
                .extracting(ColumnMetadata::getFieldName)
                .containsExactly("id");
    }

    static class NoTable {
        @Id
        Long id;
    }

    @Table("x")
    static class NoIdEntity {
        String name;
    }

    @Table("x")
    static class TwoIdEntity {
        @Id Long id;
        @Id Long anotherId;
    }

    @Table("x")
    static class WithStatic {
        public static final String CONST = "x";
        @Id
        @Column("id")
        Long id;
    }
}
