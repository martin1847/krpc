package com.bt.rpc.common.meta;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Method {
    String name;
    Property arg;
    Property res;

    List<Anno> annotations;
}