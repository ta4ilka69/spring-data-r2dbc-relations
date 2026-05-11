# core — библиотека загрузки графа сущностей для Spring Data R2DBC

Модуль реализует декларативную загрузку связанных сущностей поверх стандартного
`spring-boot-starter-data-r2dbc`, не отказываясь от его инфраструктуры
(`DatabaseClient`, `R2dbcEntityTemplate`, аннотаций `@Table`, `@Id`, `@Column`).

## Подключение

В мульти-модульном проекте проще всего использовать стартер:

```groovy
dependencies {
    implementation project(':core-starter')
    implementation 'org.springframework.boot:spring-boot-starter-data-r2dbc'
}
```

Стартер подхватывает `DatabaseClient` из автоконфигурации Spring Boot и
регистрирует следующие бины:

- `SqlGenerator` — построитель SQL-запросов для графа;
- `GraphResultSetExtractor` — сборка плоского `ResultSet` в граф объектов;
- `R2dbcGraphTemplate` — фасад с публичным API.

Для чистого `core` без стартера достаточно сделать `new R2dbcGraphTemplate(databaseClient)`.

## Описание модели

Аннотации Spring Data:

- `@Table("table_name")` — имя таблицы (по умолчанию — snake_case от имени класса).
- `@Id` — первичный ключ (требуется ровно одно поле).
- `@Column("col")` — имя колонки (по умолчанию — snake_case от имени поля).

Аннотация модуля `@R2dbcRelation`:

```java
@R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "customer_id")
private Customer customer;

@R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "orderId")
private List<OrderItem> items;
```

Поддерживаемые типы связей: `ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_ONE`.

## API

### Декларация графа

```java
RelationGraph graph = RelationGraph.builder()
    .depth(2)                                        // глубина обхода
    .include("customer")
    .include("items")
    .include("items.product")                        // вложенный путь
    .fetchStrategy(FetchStrategy.JOIN)               // или MULTI_QUERY
    .build();
```

### Запросы через `R2dbcGraphTemplate`

```java
Mono<Order>        byId   = template.findById(1L, Order.class, graph);
Flux<Order>        all    = template.findAll(Order.class, graph);
Flux<Order>        page   = template.findAll(Order.class, query, graph);
Mono<Order>        first  = template.findOne(Order.class, query, graph);
Mono<Long>         count  = template.count(Order.class, query);
```

### Описание условия и постраничной выборки

```java
Query query = Query.query(
        Criteria.where("customerId").is(42L)
                .and(Criteria.where("status").not("CANCELLED"))
    )
    .sort(Sort.by(Sort.desc("createdAt")))
    .limit(20)
    .offset(40);
```

Поддерживаются операторы `=`, `<>`, `<`, `<=`, `>`, `>=`, `IN`, `IS NULL`,
`IS NOT NULL`, а также комбинаторы `AND`/`OR`. Параметры биндятся именованными
маркерами (`:p0, :p1, ...`) — без диалекто-специфичных подстановок.

## Стратегии выборки

| Стратегия | Что делает | Когда выбирать |
|---|---|---|
| `JOIN` | Один SQL c `LEFT JOIN`-ами на каждую связь графа | Дефолт. Меньше round-trip к БД, меньше латенси |
| `MULTI_QUERY` | Отдельный SELECT по каждой связи, объединение в Java | Несколько широких `ONE_TO_MANY` коллекций (избегаем декартова взрыва) |

При `JOIN` + `LIMIT` модуль автоматически оборачивает выборку в подзапрос по `id`
корневой сущности — `LIMIT` обрезает именно сущности, а не строки декартова
произведения.

## Защитные проверки

Модуль валидирует декларацию ещё до выхода в БД:

- неизвестное поле в `Criteria`/`Sort` → `IllegalArgumentException`;
- неизвестный путь в `RelationGraph.include(...)` → `IllegalArgumentException`;
- цикл в графе (тот же тип повторно встречается на пути) → `IllegalStateException`;
- отсутствие/избыток `@Id` в сущности → `IllegalArgumentException`;
- `mappedBy` ссылается на несуществующее поле → `IllegalStateException`.

## Ограничения текущей реализации (важно для ВКР)

1. Поддерживаются связи `ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_ONE`. Связи
   `MANY_TO_MANY` через промежуточную таблицу не поддержаны намеренно —
   это естественное направление расширения и описано в `docs/roadmap.md`.
2. Записи (`save`/`update`/`delete`) делегированы штатному
   `R2dbcEntityTemplate`. Цель модуля — снизить сложность чтения сложных
   агрегатов; запись и так покрыта стандартом без боли.
3. Сортировка применяется только по полям корневой сущности.
4. Кэш метаданных — на JVM (`ConcurrentHashMap`); миграция между ClassLoader-ами
   не предусмотрена.
5. Для полного отсутствия рефлексии в горячем пути потребовался бы
   прекомпилятор/обработчик аннотаций — это отдельная задача (см. roadmap).

## Тесты

- 34 юнит-теста в `core/src/test/java` покрывают `MetadataParser`,
  `RelationGraph`, `SqlGenerator`, `GraphResultSetExtractor`, `Criteria`.
- Интеграционные сценарии в `test-app/src/test/java` поднимают PostgreSQL через
  Testcontainers и проверяют эквивалентность результата ручной агрегации,
  обе стратегии выборки и негативные кейсы.
