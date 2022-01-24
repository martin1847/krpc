package com.bt.rpc.client;

import com.bt.rpc.common.ResultWrapper;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.ClientReader;
import com.bt.rpc.serial.Serial;

/**
 * 2020-04-03 16:12
 *
 * @author Martin.C
 */
public class ClientResult extends ResultWrapper {

    ClientReader clientReader;
    Serial serial;

    public ClientResult(OutputProto output, ClientReader clientReader, Serial serial) {
        super(output);
        this.clientReader = clientReader;
        this.serial = serial;
    }

    public ClientResult(int code, String message, ClientReader clientReader, Serial serial) {
        super(code, message);
        this.clientReader = clientReader;
        this.serial = serial;
    }


    public <DTO> RpcResult<DTO> toReturn()
    {
        var res = new RpcResult<DTO>();
        res.setCode(output.getC());
        res.setMessage(output.getM());
        if(res.isOk()) {
            res.setData((DTO) clientReader.readOutput(serial, output));
        }
        return res;
    }
}
