package ru.diplom.testapp.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import ru.diplom.core.metadata.R2dbcRelation;
import ru.diplom.core.metadata.RelationType;

import java.util.List;
import java.util.UUID;

/**
 * Сущность с UUID в качестве первичного ключа. Используется в интеграционных
 * тестах, чтобы проверить, что модуль корректно работает не только с
 * числовыми идентификаторами.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tags")
public class Tag {

    @Id
    private UUID id;

    private String name;

    @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "tagId")
    private List<TagAudit> audits;
}
