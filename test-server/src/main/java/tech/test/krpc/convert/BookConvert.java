package tech.test.krpc.convert;

import java.util.HashMap;
import java.util.Map;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants.ComponentModel;
import tech.krpc.mybatis.PagedQueryHelper;
import tech.test.krpc.dto.Book;
//import org.mapstruct.Mapper;

@Mapper(componentModel = ComponentModel.JAKARTA)
public interface BookConvert extends PagedQueryHelper<Book> {
    //UserConvert INSTANCE = org.mapstruct.factory.Mappers.getMapper(UserConvert.class);

    //
    //@Mappings({
    //        @Mapping(target="name", source="dto.name")
    //})
    // TODO mapstruct auto convert to map
    @Override
    default Map<String, Object> to(Book dto) {
        var map = new HashMap<String, Object>();
        map.put("name", dto.getName());
        return map;
        //dto.getName()
    }
}