package ru.diplom.core.metadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для декларативного описания связи между сущностями 
 * в реактивных приложениях на базе Spring Data R2DBC.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface R2dbcRelation {

    /**
     * Тип связи (ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE).
     */
    RelationType type();

    /**
     * Имя поля в связанной сущности, которое владеет связью.
     * Аналог атрибута mappedBy в JPA.
     * Используется в основном для двунаправленных связей ONE_TO_MANY.
     */
    String mappedBy() default "";

    /**
     * Имя колонки в таблице БД, которая используется для объединения (foreign key).
     * Аналог аннотации @JoinColumn в JPA.
     */
    String joinColumn() default "";
}
