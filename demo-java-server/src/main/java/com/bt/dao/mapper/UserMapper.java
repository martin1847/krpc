package com.bt.dao.mapper;

import com.bt.demo.dto.User;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM USERS WHERE id = #{id}")
    User getUser(Integer id);

    @Insert("INSERT INTO USERS (id, name) VALUES (#{id}, #{name})")
    Integer createUser(Integer id,String name);

    @Delete("DELETE FROM USERS WHERE id = #{id}")
    Integer removeUser(Integer id); 
}