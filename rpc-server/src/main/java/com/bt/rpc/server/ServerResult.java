package com.bt.rpc.server;

import com.bt.rpc.common.ResultWrapper;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.serial.ServerWriter;

/**
 * 2020-04-07 13:41
 *
 * @author Martin.C
 */
public class ServerResult extends ResultWrapper {

    public <DTO> ServerResult(RpcResult<DTO> result, ServerWriter serverSerial)
    {
        if (null != result)
        {
            var output = OutputProto.newBuilder();
            output.setC(result.getCode());
            var msg = result.getMessage();
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
