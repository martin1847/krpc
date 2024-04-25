package com.bt.rpc.server;

import tech.krpc.common.ResultWrapper;
import tech.krpc.internal.OutputProto;
import tech.krpc.model.RpcResult;
import tech.krpc.serial.ServerWriter;

/**
 * 2020-04-07 13:41
 *
 * @author Martin.C
 */
public class ServerResult extends ResultWrapper {

    public ServerResult(OutputProto Output) {
        super(Output);
    }

    public ServerResult(int code, String message) {
        super(code, message);
    }

    public <DTO> ServerResult(RpcResult<DTO> result, ServerWriter serverSerial)
    {
        if (null != result)
        {
            var output = OutputProto.newBuilder();
            output.setC(result.getCode());
            var msg = result.getMsg();
            if (null != msg)
            {
                output.setM(msg);
            }
            var data = result.getData();
            if (null != data)
            {
                serverSerial.writeOutput(data, output);
            }
            super.output = output.build();
        }
    }

}
