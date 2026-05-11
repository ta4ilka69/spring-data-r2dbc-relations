package ru.diplom.core.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationGraphTest {

    @Test
    void emptyGraphIncludesNothing() {
        RelationGraph empty = RelationGraph.empty();
        assertThat(empty.getMaxDepth()).isEqualTo(1);
        assertThat(empty.getIncludePaths()).isEmpty();
        assertThat(empty.includes("anything")).isFalse();
    }

    @Test
    void includePreservesOrderAndIgnoresBlanks() {
        RelationGraph graph = RelationGraph.builder()
                .include("a")
                .include("b")
                .include("")
                .include(null)
                .include(" ")
                .build();
        assertThat(graph.getIncludePaths()).containsExactly("a", "b");
    }

    @Test
    void depthRejectsNegative() {
        assertThatThrownBy(() -> RelationGraph.builder().depth(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchStrategyDefaultsToJoin() {
        assertThat(RelationGraph.empty().getFetchStrategy())
                .isEqualTo(RelationGraph.FetchStrategy.JOIN);
    }

    @Test
    void fetchStrategyOverride() {
        RelationGraph graph = RelationGraph.builder()
                .fetchStrategy(RelationGraph.FetchStrategy.MULTI_QUERY)
                .build();
        assertThat(graph.getFetchStrategy()).isEqualTo(RelationGraph.FetchStrategy.MULTI_QUERY);
    }
}
