package com.bt.rpc.server;

import com.bt.rpc.common.AbstractContext;
import com.bt.rpc.common.FilterChain;
import com.bt.rpc.internal.InputMessage;
import io.grpc.Context;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 2020-04-07 13:38
 *
 * @author Martin.C
 */
public class ServerContext extends AbstractContext<ServerResult, InputMessage,ServerContext> {

    static final ThreadLocal<ServerContext> LOCAL = new ThreadLocal<>();

    static final List<ServerFilter> GLOBAL_FILTERS = new ArrayList<>();

    static String applicationName;

    public static void regGlobalFilter(ServerFilter filter) {
        GLOBAL_FILTERS.add(filter);
    } //;//GlobalFilters.Add(filter);


    public ServerContext(Class service, String method, Type resDto, InputMessage arg,
                         FilterChain<ServerResult,ServerContext> lastChain,
                         Function<InputMessage,Object> readInput) {
        super(service, method, resDto, arg, lastChain);
        this.readInput = readInput;
    }

    public Function<InputMessage,Object> readInput;

//    public void setReadInput(Function<InputMessage, Object[]> readInput) {
//        this.readInput = readInput;
//    }

    public static ServerContext current(){
        return  LOCAL.get();
    }

    public static String applicationName(){return  applicationName;}

    public static Context currentContext(){
        return  Context.current();
    }
}
