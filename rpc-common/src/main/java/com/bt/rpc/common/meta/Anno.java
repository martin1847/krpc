/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.common.meta;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author Martin.C
 * @version 2021/11/04 11:25 AM
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Anno {

    String name;

    Map<String,Object> properties;
}