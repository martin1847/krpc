/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.model;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 3:27 PM
 */
@Data
@NoArgsConstructor
public class PagedQuery<Query> {

    public static final Integer DEFAULT_PAGE = 1;
    public static final Integer DEFAULT_PAGE_SIZE = 10;

    Integer page;

    @NotNull@Min(1)
    Integer pageSize;

    Query q;

    public PagedQuery(Integer page, Integer pageSize) {
        this.page = page;
        this.pageSize = pageSize;
    }

    public PagedQuery(Integer page, Integer pageSize, Query q) {
        this.page = page;
        this.pageSize = pageSize;
        this.q = q;
    }
}