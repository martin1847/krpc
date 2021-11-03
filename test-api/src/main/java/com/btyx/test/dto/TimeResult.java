package com.btyx.test.dto;


import lombok.Data;

@Data
public class TimeResult{
        private String time;

        private Long timestamp;

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