package com.bt.dao.mapper;

import java.util.List;
import java.util.Map;

import com.bt.demo.dto.User;
import com.bt.mybatis.DbBounds;

public interface UserMapper {

    //单个用get/多个用list
    //获取统计值用count* 插入用save/insert；
    //删除用remove/delete；
    //修改用update
    //

    User getUser(Integer id);

    Integer createUser(Integer id,String name);

    //@Delete("DELETE FROM USER WHERE id = #{id}")
    Integer removeUser(Integer id);


    // 分页查询接口
    List<User> listBy(Map<String,Object> query, DbBounds bounds);

    //default PagedList<User> listBy(PagedQuery<User> query){
    //    var bounds = DbBounds.fromPage(query.getPage(), query.getPageSize());
    //    var list = listBy(UserConvert.INSTANCE.toQuery(query.getQ()), bounds);
    //    //HACK , this call rewrite count to bounds
    //    return new PagedList<>(bounds.getCount(), list);
    //}




}