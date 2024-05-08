package tech.krpc.gen.meta;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class
ApiMetaRoot {

    String app;
    List<Api> apis;
    List<Dto> dtos;

}