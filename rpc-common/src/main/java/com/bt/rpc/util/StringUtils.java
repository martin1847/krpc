/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.util;

/**
 *
 * @author Martin.C
 * @version 2022/01/17 11:45 AM
 */
public abstract class StringUtils {

    public static boolean isNotEmpty(String str) {
        return null != str && !str.isEmpty();
    }

    public static boolean isEmpty(String str) {
        return null == str  || str.isEmpty();
    }
    public static boolean isBlank(String str) {
        return null == str || str.isBlank();
    }
    public static boolean isNotBlank(String str) {
        return null != str && !str.isBlank();
    }

}