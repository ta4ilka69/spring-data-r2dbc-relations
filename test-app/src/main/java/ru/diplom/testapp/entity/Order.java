package ru.diplom.testapp.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.diplom.core.metadata.R2dbcRelation;
import ru.diplom.core.metadata.RelationType;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("orders")
public class Order {

    @Id
    private Long id;

    @Column("customer_id")
    private Long customerId;

    private LocalDateTime createdAt;

    // --- Связи (размечаем нашей кастомной аннотацией) ---

    // Заказ принадлежит одному клиенту (MANY_TO_ONE)
    // Связь идет по колонке customer_id в таблице orders
    @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "customer_id")
    private Customer customer;

    // Заказ имеет много позиций (ONE_TO_MANY)
    // Связь идет по полю orderId в классе OrderItem (в БД это колонка order_id)
    @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "orderId")
    private List<OrderItem> items;
}
