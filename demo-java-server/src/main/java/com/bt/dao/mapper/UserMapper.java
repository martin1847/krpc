package com.bt.dao.mapper;

import java.util.List;

import com.bt.demo.dto.User;
import com.bt.mybatis.DbBounds;
import org.apache.ibatis.annotations.Mapper;

//@Mapper
public interface UserMapper {

    //单个用get/多个用list
    //获取统计值用count* 插入用save/insert；
    //删除用remove/delete；
    //修改用update
    //

    //@Select("SELECT * FROM USER WHERE id = #{id}")
    User getUser(Integer id);

    //@Insert("INSERT INTO USER (id, name) VALUES (#{id}, #{name})")
    Integer createUser(Integer id,String name);

    //@Delete("DELETE FROM USER WHERE id = #{id}")
    Integer removeUser(Integer id);


    List<User> listBy(String name, DbBounds bounds);
}