package com.testbt.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Book {
    private Integer id;
    private String name;


    BookStatus stat;

    public Book(Integer id, String name) {
        this.id = id;
        this.name = name;
    }
}