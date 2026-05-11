package ru.diplom.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.diplom.core.graph.RelationGraph;
import ru.diplom.core.query.Criteria;
import ru.diplom.core.query.Query;
import ru.diplom.core.query.Sort;
import ru.diplom.core.testfixtures.CornerCases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Юнит-тесты corner-кейсов генератора SQL: проверяем, что заявленные
 * сценарии реально работают, а не «должны вроде бы работать».
 */
class SqlGeneratorCornerCasesTest {

    private final SqlGenerator generator = new SqlGenerator();

    @Test
    @DisplayName("Самоссылающаяся сущность: Category.parent на ограниченную глубину должна работать")
    void supportsSelfReferencingEntity() {
        RelationGraph graph = RelationGraph.builder().depth(2).include("parent").build();
        GeneratedQuery q = generator.generateSelect(CornerCases.Category.class, graph);
        assertThat(q.getSql()).contains("LEFT JOIN categories t1 ON t1.id = t0.parent_id");
    }

    @Test
    @DisplayName("Самоссылающаяся коллекция: дерево детей произвольной глубины")
    void supportsSelfReferencingCollection() {
        RelationGraph graph = RelationGraph.builder().depth(3)
                .include("children")
                .include("children.children")
                .build();
        GeneratedQuery q = generator.generateSelect(CornerCases.Category.class, graph);
        assertThat(q.getSql()).contains("LEFT JOIN categories t1");
    }

    @Test
    @DisplayName("Две связи одного типа на разные поля получают разные алиасы")
    void supportsTwoRelationsOfSameType() {
        RelationGraph graph = RelationGraph.builder().depth(1)
                .include("billingAddress")
                .include("shippingAddress")
                .build();
        GeneratedQuery q = generator.generateSelect(CornerCases.CustomerWithTwoAddresses.class, graph);
        assertThat(q.getSql())
                .contains("LEFT JOIN addresses t1 ON t1.id = t0.billing_address_id")
                .contains("LEFT JOIN addresses t2 ON t2.id = t0.shipping_address_id")
                .contains("t1.city AS billingAddress_city")
                .contains("t2.city AS shippingAddress_city");
    }

    @Test
    @DisplayName("is(null) должен генерировать IS NULL, а не = :p0 со значением null")
    void translatesIsToIsNullForNullValue() {
        Query query = Query.query(Criteria.where("status").is(null));
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql())
                .as("Comparing column with literal NULL is always UNKNOWN — must use IS NULL")
                .contains("IS NULL")
                .doesNotContain(":p0");
        assertThat(q.getBindings())
                .as("No bindings should be created for NULL comparison")
                .isEmpty();
    }

    @Test
    @DisplayName("not(null) должен генерировать IS NOT NULL")
    void translatesNotToIsNotNullForNullValue() {
        Query query = Query.query(Criteria.where("status").not(null));
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).contains("IS NOT NULL");
    }

    @Test
    @DisplayName("ORDER BY по полю присоединённой таблицы должен либо работать, либо давать понятную ошибку")
    void orderByJoinedColumn() {
        RelationGraph graph = RelationGraph.builder().depth(1).include("billingAddress").build();
        Query query = Query.empty().sort(Sort.by(Sort.asc("billingAddress.city")));
        // Текущее поведение: бросает IllegalArgumentException, потому что 'billingAddress.city'
        // не является полем корневой сущности. Это ограничение зафиксировано в README.
        // Тест документирует именно это поведение, чтобы регресс был заметен.
        assertThatThrownBy(() -> generator.generateSelectByQuery(
                CornerCases.CustomerWithTwoAddresses.class, graph, query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Criteria по полю присоединённой таблицы должна давать понятную ошибку")
    void criteriaOnJoinedColumn() {
        RelationGraph graph = RelationGraph.builder().depth(1).include("billingAddress").build();
        Query query = Query.query(Criteria.where("billingAddress.city").is("Moscow"));
        assertThatThrownBy(() -> generator.generateSelectByQuery(
                CornerCases.CustomerWithTwoAddresses.class, graph, query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LIMIT 0 не должен попадать в SQL (трактуется как «нет лимита»)")
    void zeroLimitIsIgnored() {
        Query query = Query.empty().limit(0);
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).doesNotContain("LIMIT");
    }

    @Test
    @DisplayName("OFFSET 0 не должен попадать в SQL")
    void zeroOffsetIsIgnored() {
        Query query = Query.empty().offset(0);
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).doesNotContain("OFFSET");
    }

    @Test
    @DisplayName("Длинная цепочка путей не падает и формирует читаемые префиксы")
    void supportsLongRelationPath() {
        RelationGraph graph = RelationGraph.builder().depth(4)
                .include("b")
                .include("b.c")
                .include("b.c.d")
                .build();
        GeneratedQuery q = generator.generateSelect(CornerCases.ChainA.class, graph);
        assertThat(q.getSql())
                .contains("LEFT JOIN chain_b t1")
                .contains("LEFT JOIN chain_c t2")
                .contains("LEFT JOIN chain_d t3")
                .contains("b__c__d_name");
    }

    @Test
    @DisplayName("NEQ генерирует <>")
    void renderNotEqual() {
        Query query = Query.query(Criteria.where("status").not(CornerCases.Status.ARCHIVED));
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).contains("t0.status <> :p0");
    }

    @Test
    @DisplayName("OR-композит правильно формирует скобки")
    void renderOrComposite() {
        Query query = Query.query(
                Criteria.where("status").is(CornerCases.Status.ACTIVE)
                        .or(Criteria.where("status").is(CornerCases.Status.INACTIVE))
        );
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).contains("(t0.status = :p0 OR t0.status = :p1)");
    }

    @Test
    @DisplayName("Сортировка по нескольким полям сохраняет порядок")
    void renderMultiColumnSort() {
        Query query = Query.empty().sort(Sort.by(Sort.asc("status"), Sort.desc("id")));
        GeneratedQuery q = generator.generateSelectByQuery(
                CornerCases.WithEnum.class, RelationGraph.empty(), query);
        assertThat(q.getSql()).contains("ORDER BY t0.status ASC, t0.id DESC");
    }
}
