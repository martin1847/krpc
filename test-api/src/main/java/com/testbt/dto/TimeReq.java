package com.testbt.dto;

import java.util.List;

import tech.krpc.annotation.Doc;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Doc("测试请求")
public class TimeReq {

    @Doc("姓名")
    @NotBlank
    @Size(max = 10,message = "name's length too long than 10")
    private String name;

    @Doc("年龄")
    @NotNull
    @Min(1)@Max(80)
    private Integer age;


    private String test;


    private List<Integer> test1;

    private List<Book> test2;



    @Doc(value = "test Hidden", hidden = true)
    private String hidden;
}