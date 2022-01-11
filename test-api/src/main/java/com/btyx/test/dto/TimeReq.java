package com.btyx.test.dto;

import java.util.List;

import com.bt.rpc.annotation.Doc;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

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

    private List<User> test2;



    @Doc(value = "test Hidden", hidden = true)
    private String hidden;
}