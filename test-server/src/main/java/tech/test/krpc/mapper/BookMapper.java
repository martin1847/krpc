package tech.test.krpc.mapper;

import java.util.List;
import java.util.Map;

import tech.krpc.mybatis.DbBounds;
import tech.test.krpc.dto.Book;

public interface BookMapper {

    //单个用get/多个用list
    //获取统计值用count* 插入用save
    //删除用remove
    //修改用update
    //

    Book getUser(Integer id);

    // return rows
    Integer save(Integer id,String name);

    //return rows
    boolean remove(Integer id);


    // 分页查询接口
    List<Book> listBy(Map<String,Object> query, DbBounds bounds);

    //default PagedList<User> listBy(PagedQuery<User> query){
    //    var bounds = DbBounds.fromPage(query.getPage(), query.getPageSize());
    //    var list = listBy(UserConvert.INSTANCE.toQuery(query.getQ()), bounds);
    //    //HACK , this call rewrite count to bounds
    //    return new PagedList<>(bounds.getCount(), list);
    //}




}