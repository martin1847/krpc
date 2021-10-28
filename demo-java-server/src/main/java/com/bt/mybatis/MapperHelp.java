/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.mybatis;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.bt.rpc.model.PagedList;

/**
 *
 * @author young
 * @version 2018年04月23日 11:34
 */
public abstract class MapperHelp {

    public static <DO> PagedList<DO> pageQuery(int page, int size, Function<DbBounds, List<DO>> call) {
        DbBounds bounds = DbBounds.fromPage(page, size);
        List<DO> list = call.apply(bounds);
        //HACK , this call rewrite count to bounds
        return new PagedList<>(bounds.getCount(), list);
    }

    public static <Query, DO> PagedList<DO> pageQuery(int page, int size, Query q, BiFunction<Query, DbBounds, List<DO>> call) {
        DbBounds bounds = DbBounds.fromPage(page, size);
        List<DO> list = call.apply(q, bounds);
        //HACK , this call rewrite count to bounds
        return new PagedList<>(bounds.getCount(), list);
    }

}
