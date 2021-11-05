package com.bt.rpc.server;

import com.bt.rpc.common.AbstractContext;
import com.bt.rpc.common.FilterChain;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.serial.Serial;
import io.grpc.Context;
import io.grpc.Metadata;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.validation.Validator;

/**
 * 2020-04-07 13:38
 *
 * @author Martin.C
 */
public class ServerContext extends AbstractContext<ServerResult, InputProto,ServerContext> {

    static final ThreadLocal<ServerContext> LOCAL = new ThreadLocal<>();

    static final List<ServerFilter> GLOBAL_FILTERS = new ArrayList<>();

    static Validator validator;

    static String applicationName;

    private Metadata headers;

    public static void regGlobalFilter(ServerFilter filter) {
        GLOBAL_FILTERS.add(filter);
    } //;//GlobalFilters.Add(filter);
    public static void regValidator(Validator validator) {
        ServerContext.validator = validator;
    } //;//GlobalFilters.Add(filter);



    public ServerContext(Class service, String method, Type resDto, InputProto arg,
                         FilterChain<ServerResult,ServerContext> lastChain, Metadata headers) {
        super(service, method, resDto, arg, lastChain);
        this.headers = headers;
    }


    public Metadata getHeaders(){
        return headers;
    }


    public static ServerContext current(){
        return  LOCAL.get();
    }

    public static String applicationName(){return  applicationName;}

    public static Context currentContext(){
        return  Context.current();
    }
}
