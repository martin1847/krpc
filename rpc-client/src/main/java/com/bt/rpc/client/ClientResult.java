package com.bt.rpc.client;

import tech.krpc.common.ResultWrapper;
import tech.krpc.internal.OutputProto;
import tech.krpc.model.RpcResult;
import tech.krpc.serial.ClientReader;
import tech.krpc.serial.Serial;

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
        res.setMsg(output.getM());
        if(res.isOk()) {
            res.setData((DTO) clientReader.readOutput(serial, output));
        }
        return res;
    }
}
