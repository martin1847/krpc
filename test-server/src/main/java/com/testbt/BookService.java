/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.testbt;

import com.bt.model.PagedList;
import com.bt.model.PagedQuery;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;
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