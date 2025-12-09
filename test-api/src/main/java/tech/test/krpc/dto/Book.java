package tech.test.krpc.dto;

import tech.krpc.annotation.Doc;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Book {
    private Integer id;
    private String name;


    Float lng;

    @Doc(value = "纬度")
    Float lat;


    BookStatus stat;

    Book1 book1;

    public Book(Integer id, String name) {
        this.id = id;
        this.name = name;
    }
}