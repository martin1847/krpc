package com.bt.rpc.client;

import com.bt.rpc.common.ResultWrapper;
import com.bt.rpc.internal.OutputMessage;
import com.bt.rpc.model.Code;
import com.bt.rpc.model.RpcResult;

import java.util.function.Function;

/**
 * 2020-04-03 16:12
 *
 * @author Martin.C
 */
public class ClientResult extends ResultWrapper {

    Function<OutputMessage, Object> ReadOutput;

    public ClientResult(OutputMessage output, Function<OutputMessage, Object> reader) {
        super(output);
        this.ReadOutput = reader;
    }

    public ClientResult(Code code, String message, Function<OutputMessage, Object> reader) {
        super(code, message);
        this.ReadOutput = reader;
    }


    public <DTO> RpcResult<DTO> toReturn()
    {
        var res = new RpcResult<DTO>();
        res.setCode(Code.forNumber(output.getC()));
        res.setMessage(output.getM());
        res.setData((DTO)ReadOutput.apply(output));
        return res;
    }
}
