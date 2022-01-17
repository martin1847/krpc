/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.util;

/**
 *
 * @author Martin.C
 * @version 2022/01/17 11:45 AM
 */
public abstract class StringUtils {

    public boolean isNotEmpty(String str) {
        return null != str && !str.isEmpty();
    }

    public boolean isNotBlank(String str) {
        return null != str && !str.isBlank();
    }

}