package com.bt.rpc.server;

import com.bt.rpc.common.ResultWrapper;
import com.bt.rpc.internal.OutputMessage;
import com.bt.rpc.model.RpcResult;

import java.util.function.BiConsumer;

/**
 * 2020-04-07 13:41
 *
 * @author Martin.C
 */
public class ServerResult extends ResultWrapper {

    public <DTO> ServerResult(RpcResult<DTO> result, BiConsumer<Object, OutputMessage.Builder> writeOutput)
    {
        if (null != result)
        {
            var output = OutputMessage.newBuilder();
            output.setC(result.getCode().value);
            var msg = result.getMessage();
            if (null != msg)
            {
                output.setM(msg);
            }
            var data = result.getData();
            if (null != data)
            {
                writeOutput.accept(data, output);
            }
            super.output = output.build();
        }
    }

}
