package ru.diplom.core.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriteriaTest {

    @Test
    void simpleEqBuildsComparison() {
        Criteria c = Criteria.where("name").is("foo");
        assertThat(c).isInstanceOf(Criteria.Comparison.class);
        Criteria.Comparison cmp = (Criteria.Comparison) c;
        assertThat(cmp.getFieldName()).isEqualTo("name");
        assertThat(cmp.getOperator()).isEqualTo(Criteria.ComparisonOperator.EQ);
        assertThat(cmp.getValue()).isEqualTo("foo");
    }

    @Test
    void andOrBuildComposite() {
        Criteria c = Criteria.where("a").is(1).and(Criteria.where("b").is(2));
        assertThat(c).isInstanceOf(Criteria.Composite.class);
        assertThat(((Criteria.Composite) c).getOperator()).isEqualTo(Criteria.LogicalOperator.AND);
    }

    @Test
    void inRejectsEmpty() {
        assertThatThrownBy(() -> Criteria.where("x").in(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
