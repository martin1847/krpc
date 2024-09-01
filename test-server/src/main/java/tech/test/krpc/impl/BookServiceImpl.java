/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.test.krpc.impl;

import tech.test.krpc.convert.BookConvert;
import tech.test.krpc.dto.Book;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import tech.krpc.model.PagedList;
import tech.krpc.model.PagedQuery;
import tech.krpc.server.Filters;
import tech.krpc.model.RpcResult;
import tech.test.krpc.BookService;
import tech.test.krpc.filter.TestFilter2;
import tech.test.krpc.mapper.BookMapper;
import io.quarkus.runtime.Startup;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 10:55 AM
 */
@ApplicationScoped
@Startup
@Filters(TestFilter2.class)
public class BookServiceImpl implements BookService {

    @Inject //by the ext
    BookMapper bookMapper;

    @Inject
    BookConvert bookConvert;



    @Override
    public RpcResult<Book> getBook(Integer id) {
        return RpcResult.ok(bookMapper.getUser(id));
    }


    @Override
    public RpcResult<PagedList<Book>> listBook(PagedQuery<Book> query) {
        //var list = Mappers.pager(query.getPage(), query.getPageSize(), query.getQ(), userMapper::listBy);
        //return RpcResult.ok(list);
        var list  = bookConvert.pager(query, bookMapper::listBy);


        //var pl = ((PageQueryHelper<User>) dto -> {
        //    var map = new HashMap<String,Object>();
        //    return map;
        //});

        //var pl = PagedQueryHelper.pager(query,
        //        //map,//
        //        (dto,m) -> m.put("name",dto.getName()),
        //        userMapper::listBy);

        //var bounds = DbBounds.fromPage(query.getPage(), query.getPageSize());
        //var qMap = userConvert.toQuery(query.getQ());
        //System.out.println(qMap);
        //var list = userMapper.listBy(new HashMap<>(), bounds);
        //HACK , this call rewrite count to bounds
        //var pl = new  PagedList<>(bounds.getCount(), list);

        return RpcResult.ok(list);
    }

    @Override
    @Transactional//(REQUIRED) (default):
    public RpcResult<Integer> saveBook(Book u) {
        var del = bookMapper.remove(u.getId());
        System.out.println("del : " + del);
        var id = bookMapper.save(u.getId(),u.getName());
        return RpcResult.ok(id);
    }

}