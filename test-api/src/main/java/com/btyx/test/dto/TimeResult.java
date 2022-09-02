package com.btyx.test.dto;


import java.util.Date;

import javax.validation.constraints.NotNull;

import com.bt.rpc.annotation.Doc;
import lombok.Data;

@Data
public class TimeResult{
        private String time;

        @NotNull
        private Long timestamp;

        @Doc("会被序列化为毫秒时间戳")
        private Date date;

//        private Date timestap = new Date();
//
//    private LocalDateTime localDateTime = LocalDateTime.now();
//
//    private LocalDate localDate = LocalDate.now();
//
//
//
//    public static void main(String[] args) {
//        System.out.println(
//                SerializationUtils.serialize(new TimeResult()).toStringUtf8()
//        );
//    }
}