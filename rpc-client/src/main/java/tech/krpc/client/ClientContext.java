package tech.krpc.client;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.grpc.CallOptions;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.Data;
import tech.krpc.common.AbstractContext;
import tech.krpc.common.FilterChain;
import tech.krpc.internal.SerialEnum;
import tech.krpc.model.RpcResult;

/**
 * 2020-04-03 16:12
 *
 * @author Martin.C
 */
@Data
public class ClientContext extends AbstractContext<ClientResult,Object[],ClientContext> {


    static final FastThreadLocal<ClientContext> LOCAL = new FastThreadLocal<>();

    static final FastThreadLocal<CallOptions> OPTION_LOCAL = new FastThreadLocal<>();

    static final List<ClientFilter> GLOBAL_FILTERS = new ArrayList<>();


    public static void regGlobalFilter(ClientFilter filter) {
        GLOBAL_FILTERS.add(filter);
    } //;//GlobalFilters.Add(filter);


    private SerialEnum serial;
    private CallOptions callOptions;

    /**
     * only use in filter , after the context create
      */
    public CallOptions getCallOptions(){
        return callOptions;//CallOptions.DEFAULT;
    }


    public ClientContext(Class service, String method, Type resDto, Object[] arg
            , FilterChain<ClientResult,ClientContext> lastChain
        ,SerialEnum serialEnum) {
        super(service, method, resDto, arg, lastChain);
        this.serial = serialEnum;
        var opt = OPTION_LOCAL.get();
        if(null == opt){
            opt = CallOptions.DEFAULT;
        }
        this.callOptions = opt;
    }

    public static ClientContext current(){
        return  LOCAL.get();
    }

    /**
     * 设置超时时间，或者其他一些设置
     */
    public static <DTO> RpcResult<DTO> withCallOptions(CallOptions opt, Supplier<RpcResult<DTO>> supplier) {
        try {
            OPTION_LOCAL.set(opt);
            return supplier.get();
        } finally {
            OPTION_LOCAL.remove();
        }
    }
}
