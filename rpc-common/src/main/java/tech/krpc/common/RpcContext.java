package tech.krpc.common;

import java.lang.reflect.Type;

/**
 * 2020-04-03 15:25
 *
 * @author Martin.C
 */
public interface RpcContext<Res> {

    Res underlyCall() throws  Throwable;


    // Summary:
    //     Gets the type of the service to call
    Class getService();
    //
    // Summary:
    //     Gets the name of the method.
    String getMethod();

    //
    // Summary:
    //     Gets the underly DTO type of RpcResult<DTO>
    Type getResDto();
}
