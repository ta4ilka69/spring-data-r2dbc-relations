# spring-data-r2dbc-relations

> Declarative loading of related entities for Spring Data R2DBC. Fully non-blocking, two fetch strategies, Spring Boot auto-configuration in one dependency.

`spring-data-r2dbc-graph` is an extension module for [Spring Data R2DBC](https://docs.spring.io/spring-data/r2dbc/docs/current/reference/html/) that closes a long-standing gap: there is no first-class way to load a graph of related entities in a reactive, non-blocking fashion. The official issue [spring-projects/spring-data-relational#1834](https://github.com/spring-projects/spring-data-relational/issues/1834) tracking this feature has been open since 2019.

This module provides:

- a single annotation, `@R2dbcRelation`, to declare relations on entity fields;
- an immutable `RelationGraph` builder to describe **which** relations to load and **how deep**, per request;
- a high-level reactive facade `R2dbcGraphTemplate` returning `Mono<T>` / `Flux<T>` with the requested graph fully assembled;
- two interchangeable fetch strategies, `JOIN` and `MULTI_QUERY`, selectable per request;
- Spring Boot 3.x auto-configuration via a separate starter.

The module is **not** a JPA replacement and intentionally does **not** introduce a managed-context / lazy-proxy model. All loading is explicit; the assembled graph is plain immutable data.

---

## Status

Bachelor's thesis project (ITMO University, 2026). API is stable for the use cases covered by the test suite, but the module is **pre-1.0** and has not yet been published to Maven Central — see [Roadmap](#roadmap).

---

## Why

In a typical reactive Spring application using Spring Data R2DBC, loading an aggregate with its children requires manual orchestration of multiple reactive streams:

```java
public Mono<Order> findOrderGraph(Long id) {
    return orderRepository.findById(id).flatMap(order ->
        Mono.zip(
            customerRepository.findById(order.getCustomerId()),
            itemRepository.findAllByOrderId(order.getId()).collectList()
        ).map(t -> {
            order.setCustomer(t.getT1());
            order.setItems(t.getT2());
            return order;
        }));
}
```

This pattern is repeated for every new combination of relations, scales poorly with depth, and is easy to get wrong on edge cases (empty collections, missing parents, duplicate children). On a representative sample application this module reduces service-layer code by roughly 2× on basic graph loading and by up to ~5× on scenarios with the same set of operations.

---

## Requirements

- Java 17 or later
- Spring Boot 3.2 or later
- Spring Data R2DBC 3.2 or later
- A reactive R2DBC driver (tested with PostgreSQL via [`r2dbc-postgresql`](https://github.com/pgjdbc/r2dbc-postgresql))

---

## Installation

The module is not yet on Maven Central. For the time being, build from source and publish to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then add either the starter (recommended) or the core library to your application.

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("ru.diplom:r2dbc-graph-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

**Maven:**

```xml
<dependency>
    <groupId>ru.diplom</groupId>
    <artifactId>r2dbc-graph-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The starter auto-configures `R2dbcGraphTemplate` as soon as a `DatabaseClient` bean is present in the context. No further setup is required.

If you don't use Spring Boot, depend on `r2dbc-graph-core` directly and instantiate `R2dbcGraphTemplate` yourself.

---

## Quick start

### 1. Annotate your entities

```java
@Table("orders")
public class Order {

    @Id
    private Long id;

    @Column("customer_id")
    private Long customerId;

    @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "customer_id")
    private Customer customer;

    @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "orderId")
    private List<OrderItem> items;

    // getters/setters
}
```

Three relation types are supported: `MANY_TO_ONE`, `ONE_TO_ONE`, `ONE_TO_MANY`. Exactly one of `joinColumn` or `mappedBy` is required, which fully disambiguates the direction of the relation without needing to inspect the opposite side.

### 2. Inject the template

```java
@Service
public class OrderService {

    private final R2dbcGraphTemplate graphTemplate;

    public OrderService(R2dbcGraphTemplate graphTemplate) {
        this.graphTemplate = graphTemplate;
    }

    public Mono<Order> findOrderWithItems(Long orderId) {
        RelationGraph graph = RelationGraph.builder()
                .include("customer")
                .include("items")
                .include("items.product")
                .depth(2)
                .build();

        return graphTemplate.findById(orderId, Order.class, graph);
    }
}
```

That's it. The returned `Mono<Order>` resolves to an `Order` instance with its `customer`, `items`, and each item's `product` fully populated, in a single non-blocking pipeline.

---

## Core concepts

### `@R2dbcRelation`

Marks an entity field as a relation. The annotation does not pull in JPA semantics — there is no lazy proxy, no managed context, no flush. It exists solely as metadata for the loader.

| Attribute    | Type            | Used for                          | Notes                                                       |
|--------------|-----------------|------------------------------------|-------------------------------------------------------------|
| `type`       | `RelationType`  | direction & cardinality           | `MANY_TO_ONE`, `ONE_TO_ONE`, `ONE_TO_MANY`                  |
| `joinColumn` | `String`        | `MANY_TO_ONE` / `ONE_TO_ONE`      | FK column name on the owning side                            |
| `mappedBy`   | `String`        | `ONE_TO_MANY`                     | name of the field on the child side that owns the FK         |

`MANY_TO_MANY` is intentionally out of scope (see [Limitations](#limitations)).

### `RelationGraph`

Describes which relations to load, per request:

- `include(String path)` — dotted path of a relation to fetch (e.g. `"items.product"`). Paths are validated against the entity metadata at execution time; an unmatched path produces an explicit `IllegalArgumentException` rather than a silently incomplete result.
- `depth(int max)` — hard cap on traversal depth; protects against accidental loading of cyclic structures and from typos in deeply nested paths.
- `fetchStrategy(FetchStrategy)` — selects between `JOIN` (default) and `MULTI_QUERY`, see below.

`RelationGraph` instances are immutable and cheap to share or cache.

### `R2dbcGraphTemplate`

The high-level facade. The current API surface:

```java
<T> Mono<T>  findById     (Object id, Class<T> rootType, RelationGraph graph);
<T> Flux<T>  findAll      (Class<T> rootType, RelationGraph graph);
<T> Flux<T>  findAllBy    (Class<T> rootType, Criteria criteria, RelationGraph graph);
```

All methods return reactive types directly; no `block()` is ever called internally.

---

## Fetch strategies

### `JOIN` (default)

One composite `SELECT` with `LEFT JOIN`s for every requested relation. Column aliases (`t0.id AS root_id`, `t1.id AS customer_id`, …) guarantee no collisions in the result set regardless of how many tables participate.

**When to prefer:** small-to-medium child collections, latency-sensitive deployments where round-trips to the database dominate cost.

**Pagination caveat:** when paginating a root entity that has `ONE_TO_MANY` relations, the module rewrites the query as `SELECT … WHERE root.id IN (<paged subquery>)` so that `LIMIT` bounds the number of root entities, not the cartesian rows.

### `MULTI_QUERY`

One query per level of the graph. Within a level, child queries are dispatched in parallel via `Mono.when`. Avoids the cartesian-product blow-up that `LEFT JOIN` over multiple `ONE_TO_MANY` relations produces.

**When to prefer:** several large `ONE_TO_MANY` collections, database co-located with the application, low per-query overhead.

Switching strategies is a one-line change in the `RelationGraph` builder; the rest of the application code is unaffected.

---

## How the assembled graph is built

The pipeline is intentionally simple and easy to reason about:

1. `MetadataParser` introspects the entity once and caches an immutable `EntityMetadata`.
2. `SqlGenerator` derives a `SelectQueryContext` (table nodes, aliases, column prefixes) and produces SQL.
3. `DatabaseClient` from Spring Data R2DBC executes the query non-blockingly.
4. `GraphResultSetExtractor` materialises the rows (`collectList`) and folds them into an object graph using a per-request `Map<path, Map<id, instance>>` cache.
5. The result is republished as `Mono<T>` / `Flux<T>` back into the reactive pipeline.

The materialisation step is the same compromise that Spring Data Relational's own maintainers point out in [#1834](https://github.com/spring-projects/spring-data-relational/issues/1834) — building an object graph requires knowing the full set of rows. The module isolates this trade-off in a single component; the rest of the pipeline remains streaming and non-blocking.

Algorithmic complexity of the assembly step is `O(N · M)` where `N` is the number of rows and `M` is the number of nodes in the load graph — asymptotically optimal for the task.

---

## Limitations

- **No `MANY_TO_MANY`.** Bridge tables come with too many degrees of freedom (attributes / no attributes, hidden / explicit linking entity) to ship a satisfactory declarative API in a first release. Planned, not yet implemented.
- **No filtering inside loaded collections.** Filtering is applied to the root entity only. Loading "orders of customer X, but only items priced > 100" requires a custom query for now.
- **No write-side graph operations.** The module is read-only by design; mutations should use the existing Spring Data R2DBC repositories.
- **Snake-case column convention.** Column names are derived from field names via `lowerCamelCase` → `snake_case`. Overriding per column via `@Column` is supported on root attributes; relation FK names are taken verbatim from `@R2dbcRelation`.

---

## Examples

A full working example is provided in the `test-app` module of this repository: domain model, schema, service layer, integration tests with Testcontainers + PostgreSQL.

---

## Roadmap

- `MANY_TO_MANY` support with explicit and implicit join entities.
- Per-relation filtering and projections in the load graph builder.
- Benchmark suite comparing `JOIN`, `MULTI_QUERY`, and a hand-rolled aggregation baseline on synthetic workloads.
- Publication to Maven Central as `1.0.0`.

Contributions and feedback in issues / PRs are welcome.

---

## Related

- Tracking issue: [spring-projects/spring-data-relational#1834](https://github.com/spring-projects/spring-data-relational/issues/1834) — *"Support for entity associations in R2DBC"*.
- [Spring Data R2DBC Reference Documentation](https://docs.spring.io/spring-data/r2dbc/docs/current/reference/html/)
- [R2DBC Specification](https://r2dbc.io/spec/)
- [Project Reactor Reference](https://projectreactor.io/docs/core/release/reference/)

---

## License

MIT. See `LICENSE` for the full text.
