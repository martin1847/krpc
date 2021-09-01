package com.bt.rpc.common.meta;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
    @AllArgsConstructor
    @NoArgsConstructor
public     class Property {
        /// name
        String name;

        /// type
        Dto type;

        /// GetCustomAttributesData
        List<String> annotations;
    }