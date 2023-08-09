package com.testbt.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class User {
    private Integer id;
    private String name;


    UserStatus stat;

    public User(Integer id, String name) {
        this.id = id;
        this.name = name;
    }
}