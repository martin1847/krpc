package com.testbt.convert;

import java.util.HashMap;
import java.util.Map;

import com.testbt.dto.User;
import com.bt.mybatis.PagedQueryHelper;
import org.mapstruct.Mapper;
//import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface UserConvert extends PagedQueryHelper<User> {
    //UserConvert INSTANCE = org.mapstruct.factory.Mappers.getMapper(UserConvert.class);

    //
    //@Mappings({
    //        @Mapping(target="name", source="dto.name")
    //})
    // TODO mapstruct auto convert to map
    @Override
    default Map<String, Object> to(User dto) {
        var map = new HashMap<String, Object>();
        map.put("name", dto.getName());
        return map;
        //dto.getName()
    }
}