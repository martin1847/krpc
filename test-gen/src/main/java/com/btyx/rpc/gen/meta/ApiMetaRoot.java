package com.btyx.rpc.gen.meta;

import java.util.List;

import com.bt.rpc.common.RpcConstants;
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