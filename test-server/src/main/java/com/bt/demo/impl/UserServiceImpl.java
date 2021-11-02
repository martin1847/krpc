/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.demo.impl;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;

import com.bt.convert.UserConvert;
import com.bt.dao.mapper.UserMapper;
import com.bt.demo.UserService;
import com.bt.demo.dto.User;
import com.bt.model.PagedList;
import com.bt.model.PagedQuery;
import com.bt.rpc.model.RpcResult;
import io.quarkus.runtime.Startup;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 10:55 AM
 */
@ApplicationScoped
@Startup
public class UserServiceImpl implements UserService {

    @Inject //by the ext
    UserMapper userMapper;

    @Inject
    UserConvert userConvert;



    @Override
    public RpcResult<User> getUser(Integer id) {
        return RpcResult.ok(userMapper.getUser(id));
    }

    @Override
    public RpcResult<PagedList<User>> listUser(PagedQuery<User> query) {
        //var list = Mappers.pager(query.getPage(), query.getPageSize(), query.getQ(), userMapper::listBy);
        //return RpcResult.ok(list);
        var list  = userConvert.pager(query,userMapper::listBy);


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
    public RpcResult<Integer> saveUser(User u) {
        var del = userMapper.remove(u.getId());
        System.out.println("del : " + del);
        var id = userMapper.save(u.getId(),u.getName());
        return RpcResult.ok(id);
    }

}