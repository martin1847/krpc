/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.filter;

/**
 * 把一个拦截器注册为全局拦截器,按照value制定的顺序，越小越在前面。
 * 否则按照名字排序。
 * 只能标记给 RpcFilter
 *
 * @author Martin.C
 * @version 2021/11/05 1:43 PM
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GlobalFilter {

    /**
     * 数组顺序，越小越在前面。
     */
    int value() default 0;

    class Order implements Comparable<Order> {

        public final int    val;
        public final String name;

        public Order(int val, String name) {
            this.val = val;
            this.name = name;
        }

        @Override
        public int compareTo(Order other) {
            var res = Integer.compare(val, other.val);
            if (res == 0) {
                return name.compareTo(other.name);
            }
            return res;
        }

    }

}