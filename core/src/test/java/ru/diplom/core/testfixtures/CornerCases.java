package ru.diplom.core.testfixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.diplom.core.metadata.R2dbcRelation;
import ru.diplom.core.metadata.RelationType;

import java.util.List;

/**
 * Набор сущностей для проверки corner-кейсов парсера, генератора и сборщика графа.
 * Все классы намеренно собраны в одном файле — они не несут ценности по отдельности
 * и используются только в тестах.
 */
public final class CornerCases {

    private CornerCases() {
    }

    /** Самоссылающаяся сущность: дерево категорий. */
    @Table("categories")
    public static class Category {
        @Id
        private Long id;

        private String name;

        @Column("parent_id")
        private Long parentId;

        @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "parent_id")
        private Category parent;

        @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "parentId")
        private List<Category> children;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public Category getParent() { return parent; }
        public void setParent(Category parent) { this.parent = parent; }
        public List<Category> getChildren() { return children; }
        public void setChildren(List<Category> children) { this.children = children; }
    }

    /** Адресная сущность для проверки двух связей одного типа. */
    @Table("addresses")
    public static class Address {
        @Id
        private Long id;
        private String city;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
    }

    /** Сущность с двумя связями одного типа на разные поля. */
    @Table("customers")
    public static class CustomerWithTwoAddresses {
        @Id
        private Long id;

        @Column("billing_address_id")
        private Long billingAddressId;

        @Column("shipping_address_id")
        private Long shippingAddressId;

        @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "billing_address_id")
        private Address billingAddress;

        @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "shipping_address_id")
        private Address shippingAddress;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getBillingAddressId() { return billingAddressId; }
        public Long getShippingAddressId() { return shippingAddressId; }
        public Address getBillingAddress() { return billingAddress; }
        public Address getShippingAddress() { return shippingAddress; }
    }

    public enum Status { ACTIVE, INACTIVE, ARCHIVED }

    @Table("with_enum")
    public static class WithEnum {
        @Id
        private Long id;
        private Status status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }
    }

    /** Поле-примитив, чтобы проверить поведение при NULL из базы. */
    @Table("with_primitive")
    public static class WithPrimitive {
        @Id
        private Long id;

        @Column("count_value")
        private long count;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
    }

    /** Базовый класс с @Id для проверки наследования полей. */
    public abstract static class BaseEntity {
        @Id
        private Long id;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    @Table("child")
    public static class ChildEntity extends BaseEntity {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    /** @Transient должен быть исключён из колонок. */
    @Table("with_transient")
    public static class WithTransient {
        @Id
        private Long id;
        private String name;

        @Transient
        private String computed;

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getComputed() { return computed; }
    }

    /** @Column("") должен использовать имя поля в snake_case. */
    @Table("with_empty_column")
    public static class WithEmptyColumn {
        @Id
        private Long id;

        @Column("")
        private String displayName;

        public Long getId() { return id; }
        public String getDisplayName() { return displayName; }
    }

    /** @R2dbcRelation типа ONE_TO_MANY на одиночное поле — должно быть отвергнуто. */
    @Table("bad_relation")
    public static class OneToManyOnScalarField {
        @Id
        private Long id;

        @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "ownerId")
        private Address single;

        public Long getId() { return id; }
        public Address getSingle() { return single; }
    }

    /** Raw List без параметра типа — должна быть осмысленная ошибка. */
    @Table("raw_relation")
    @SuppressWarnings("rawtypes")
    public static class RawListRelation {
        @Id
        private Long id;

        @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "ownerId")
        private List items;

        public Long getId() { return id; }
        public List getItems() { return items; }
    }

    /** Глубокая цепочка путей a -> b -> c -> d -> e. */
    @Table("chain_a")
    public static class ChainA {
        @Id
        private Long id;

        @Column("b_id")
        private Long bId;

        @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "b_id")
        private ChainB b;

        public Long getId() { return id; }
        public ChainB getB() { return b; }
    }

    @Table("chain_b")
    public static class ChainB {
        @Id
        private Long id;

        @Column("c_id")
        private Long cId;

        @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "c_id")
        private ChainC c;

        public Long getId() { return id; }
        public ChainC getC() { return c; }
    }

    @Table("chain_c")
    public static class ChainC {
        @Id
        private Long id;

        @Column("d_id")
        private Long dId;

        @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "d_id")
        private ChainD d;

        public Long getId() { return id; }
        public ChainD getD() { return d; }
    }

    @Table("chain_d")
    public static class ChainD {
        @Id
        private Long id;
        private String name;

        public Long getId() { return id; }
        public String getName() { return name; }
    }
}
