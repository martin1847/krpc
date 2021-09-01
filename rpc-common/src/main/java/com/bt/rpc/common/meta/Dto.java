package com.bt.rpc.common.meta;

import lombok.Data;

import java.util.List;

@Data
public class Dto {
    String name;
    List<Property> fields;
}