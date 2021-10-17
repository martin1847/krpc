package com.bt.rpc.client;

import com.bt.rpc.common.ResultWrapper;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.Code;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.ClientSerial;

import java.util.function.Function;

/**
 * 2020-04-03 16:12
 *
 * @author Martin.C
 */
public class ClientResult extends ResultWrapper {

    ClientSerial clientSerial;

    public ClientResult(OutputProto output, ClientSerial clientSerial) {
        super(output);
        this.clientSerial = clientSerial;
    }

    public ClientResult(Code code, String message, ClientSerial clientSerial) {
        super(code, message);
        this.clientSerial = clientSerial;
    }


    public <DTO> RpcResult<DTO> toReturn()
    {
        var res = new RpcResult<DTO>();
        res.setCode(Code.forNumber(output.getC()));
        res.setMessage(output.getM());
        res.setData((DTO)clientSerial.readOutput(output));
        return res;
    }
}
