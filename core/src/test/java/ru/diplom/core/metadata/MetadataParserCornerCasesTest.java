package ru.diplom.core.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.diplom.core.testfixtures.CornerCases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Юнит-тесты corner-кейсов парсера метаданных. Цель — зафиксировать,
 * какие сценарии модуль действительно поддерживает, а какие сломаны
 * и требуют доработки до защиты ВКР.
 */
class MetadataParserCornerCasesTest {

    @Test
    @DisplayName("Наследование: @Id из BaseEntity должен подхватываться")
    void inheritsIdFromBaseClass() {
        EntityMetadata meta = MetadataParser.parse(CornerCases.ChildEntity.class);
        assertThat(meta.getIdColumn()).isNotNull();
        assertThat(meta.getIdFieldName()).isEqualTo("id");
        assertThat(meta.getColumnByFieldName("name")).isNotNull();
    }

    @Test
    @DisplayName("@Transient поле не должно становиться колонкой")
    void respectsTransientAnnotation() {
        EntityMetadata meta = MetadataParser.parse(CornerCases.WithTransient.class);
        assertThat(meta.getColumnByFieldName("computed"))
                .as("@Transient field should be excluded from columns")
                .isNull();
    }

    @Test
    @DisplayName("@Column(\"\") должен трактоваться как отсутствующее имя (snake_case fallback)")
    void emptyColumnAnnotationFallsBackToSnakeCase() {
        EntityMetadata meta = MetadataParser.parse(CornerCases.WithEmptyColumn.class);
        ColumnMetadata column = meta.getColumnByFieldName("displayName");
        assertThat(column).isNotNull();
        assertThat(column.getColumnName())
                .as("Empty @Column should not produce empty column name")
                .isEqualTo("display_name");
    }

    @Test
    @DisplayName("Enum-поле распознаётся как обычная колонка")
    void parsesEnumColumn() {
        EntityMetadata meta = MetadataParser.parse(CornerCases.WithEnum.class);
        ColumnMetadata column = meta.getColumnByFieldName("status");
        assertThat(column).isNotNull();
        assertThat(column.getColumnName()).isEqualTo("status");
        assertThat(column.getField().getType()).isEqualTo(CornerCases.Status.class);
    }

    @Test
    @DisplayName("ONE_TO_MANY на не-Collection поле должно отвергаться")
    void rejectsOneToManyOnScalarField() {
        assertThatThrownBy(() -> MetadataParser.parse(CornerCases.OneToManyOnScalarField.class))
                .as("ONE_TO_MANY must require Collection field")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ONE_TO_MANY на сырой List должен давать понятную ошибку")
    void failsClearlyOnRawList() {
        assertThatThrownBy(() -> MetadataParser.parse(CornerCases.RawListRelation.class))
                .as("Raw List collection should produce a clear error mentioning the field")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
    }

    @Test
    @DisplayName("Самоссылающаяся сущность парсится без падения")
    void parsesSelfReferencingEntity() {
        EntityMetadata meta = MetadataParser.parse(CornerCases.Category.class);
        assertThat(meta.getRelation("parent")).isNotNull();
        assertThat(meta.getRelation("parent").getTargetType()).isEqualTo(CornerCases.Category.class);
        assertThat(meta.getRelation("children")).isNotNull();
        assertThat(meta.getRelation("children").getTargetType()).isEqualTo(CornerCases.Category.class);
    }

    @Test
    @DisplayName("Две связи одного типа на разные поля не конфликтуют")
    void parsesTwoRelationsOfSameType() {
        EntityMetadata meta = MetadataParser.parse(CornerCases.CustomerWithTwoAddresses.class);
        assertThat(meta.getRelations()).hasSize(2);
        assertThat(meta.getRelation("billingAddress").getJoinColumn()).isEqualTo("billing_address_id");
        assertThat(meta.getRelation("shippingAddress").getJoinColumn()).isEqualTo("shipping_address_id");
    }
}
