package com.btyx.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Size;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TimeReq {

    @Size(max = 10,message = "toooooooooooo  java")
    private String name;
    private int age;
}