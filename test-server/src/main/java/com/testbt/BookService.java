/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.testbt;

import tech.krpc.model.PagedList;
import tech.krpc.model.PagedQuery;
import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;
import com.testbt.dto.Book;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 10:52 AM
 */
@RpcService
//@UnsafeWeb
public interface BookService {

    RpcResult<Book> getBook(Integer id);

    RpcResult<PagedList<Book>> listBook(PagedQuery<Book> query);

    RpcResult<Integer> saveBook(Book u);

}