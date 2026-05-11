package ru.diplom.testapp.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;
import ru.diplom.core.metadata.R2dbcRelation;
import ru.diplom.core.metadata.RelationType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("order_items")
public class OrderItem {

    @Id
    private Long id;

    @Column("order_id")
    private Long orderId;

    @Column("product_id")
    private Long productId;

    private Integer quantity;

    // --- Связи ---

    @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "product_id")
    private Product product;
}
