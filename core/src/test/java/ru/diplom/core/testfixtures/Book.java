package ru.diplom.core.testfixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.diplom.core.metadata.R2dbcRelation;
import ru.diplom.core.metadata.RelationType;

import java.util.Objects;

@Table("books")
public class Book {

    @Id
    private Long id;

    private String title;

    @Column("author_id")
    private Long authorId;

    @Column("publisher_id")
    private Long publisherId;

    @R2dbcRelation(type = RelationType.MANY_TO_ONE, joinColumn = "publisher_id")
    private Publisher publisher;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public Long getPublisherId() { return publisherId; }
    public void setPublisherId(Long publisherId) { this.publisherId = publisherId; }
    public Publisher getPublisher() { return publisher; }
    public void setPublisher(Publisher publisher) { this.publisher = publisher; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Book b)) return false;
        return Objects.equals(id, b.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
