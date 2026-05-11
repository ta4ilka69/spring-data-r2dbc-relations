package ru.diplom.core.testfixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.diplom.core.metadata.R2dbcRelation;
import ru.diplom.core.metadata.RelationType;

import java.util.List;

@Table("authors")
public class Author {

    @Id
    private Long id;

    private String name;

    @Column("country_code")
    private String countryCode;

    @R2dbcRelation(type = RelationType.ONE_TO_MANY, mappedBy = "authorId")
    private List<Book> books;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public List<Book> getBooks() { return books; }
    public void setBooks(List<Book> books) { this.books = books; }
}
