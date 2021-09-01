package com.bt.rpc.client;

import com.bt.rpc.common.AbstractContext;
import com.bt.rpc.common.FilterChain;
import io.grpc.CallOptions;
import lombok.Data;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 2020-04-03 16:12
 *
 * @author Martin.C
 */
@Data
public class ClientContext extends AbstractContext<ClientResult,Object[],ClientContext> {


    static final ThreadLocal<ClientContext> LOCAL = new ThreadLocal<>();


    static final List<ClientFilter> GLOBAL_FILTERS = new ArrayList<>();


    public static void regGlobalFilter(ClientFilter filter) {
        GLOBAL_FILTERS.add(filter);
    } //;//GlobalFilters.Add(filter);


    public CallOptions getCallOptions(){
        return CallOptions.DEFAULT;
    }


    public ClientContext(Class service, String method, Type resDto, Object[] arg, FilterChain<ClientResult,ClientContext> lastChain) {
        super(service, method, resDto, arg, lastChain);
    }

    public static ClientContext current(){
        return  LOCAL.get();
    }

}
