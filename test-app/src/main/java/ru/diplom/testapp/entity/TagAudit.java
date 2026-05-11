package ru.diplom.testapp.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Сущность-ребёнок {@link Tag}, где внешний ключ также хранится как UUID.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tag_audits")
public class TagAudit {

    @Id
    private Long id;

    @Column("tag_id")
    private UUID tagId;

    private String note;
}
