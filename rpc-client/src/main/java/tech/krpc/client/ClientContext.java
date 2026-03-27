package tech.krpc.client;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
public class ClientContext extends AbstractContext<ClientResult, Object[], ClientContext> {

    static final FastThreadLocal<ClientContext> LOCAL = new FastThreadLocal<>();

    static final FastThreadLocal<CallOptions> OPTION_LOCAL = new FastThreadLocal<>();

    static final List<ClientFilter> GLOBAL_FILTERS = new ArrayList<>();

    /**
     *
     *     比如实现全局超时时间；
     *     自定义超时时间；
     *     public ClientResult Invoke(ClientContext clientContext, FilterChain<ClientResult, ClientContext> next) throws Throwable {
     *
     *         var opt = clientContext.getCallOptions().withDeadlineAfter(500, TimeUnit.MICROSECONDS);
     *         //由于CallOptions被设置为Immutable，修改后要重复赋值才生效
     *         clientContext.setCallOptions(opt);
     */
    public static void regGlobalFilter(ClientFilter filter) {
        GLOBAL_FILTERS.add(filter);
    } //;//GlobalFilters.Add(filter);

    private SerialEnum  serial;


    private CallOptions callOptions;

    /**
     * only use in filter , after the context create
     */
    public CallOptions getCallOptions() {
        return callOptions;//CallOptions.DEFAULT;
    }

    /**
     * 由于CallOptions被设置了Immutable，修改后要重复赋值
     */
    public void setCallOptions(CallOptions callOptions) {
        this.callOptions = callOptions;
    }


    public ClientContext(Class service, String method, Type resDto, Object[] arg
            , FilterChain<ClientResult, ClientContext> lastChain
            , SerialEnum serialEnum) {
        super(service, method, resDto, arg, lastChain);
        this.serial = serialEnum;
        var opt = OPTION_LOCAL.get();
        if (null == opt) {
            opt = CallOptions.DEFAULT;
        }
        this.callOptions = opt;
    }

    public static ClientContext current() {
        return LOCAL.get();
    }

    /**
     * 设置超时时间，或者其他一些设置
     * var opt = CallOptions.DEFAULT
     *                 .withDeadlineAfter(500, TimeUnit.MICROSECONDS);
     *  ClientContext.withCallOptions(opt,demoRpc::name);
     */
    public static <DTO> RpcResult<DTO> withCallOptions(CallOptions opt, Supplier<RpcResult<DTO>> supplier) {
        try {
            OPTION_LOCAL.set(opt);
            return supplier.get();
        } finally {
            OPTION_LOCAL.remove();
        }
    }

    /**
     * 设置超时时间，或者其他一些设置
     var opt = CallOptions.DEFAULT
     *                 .withDeadlineAfter(500, TimeUnit.MICROSECONDS);
     *  ClientContext.withCallOptions(opt,demoRpc::hello,new TimeReq());
     */
    public static <Param, DTO> RpcResult<DTO> withCallOptions(CallOptions opt, Function<Param, RpcResult<DTO>> rpcCall, Param param) {
        try {
            OPTION_LOCAL.set(opt);
            return rpcCall.apply(param);
        } finally {
            OPTION_LOCAL.remove();
        }
    }
}
