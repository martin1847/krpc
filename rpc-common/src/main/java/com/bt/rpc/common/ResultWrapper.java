package com.bt.rpc.common;

import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.Code;

public class ResultWrapper
    {

        public OutputProto output;

        public ResultWrapper(){}

        public ResultWrapper(OutputProto Output)
        {
            this.output = Output;
        }

        public ResultWrapper(Code code, String message)
        {
            var out = OutputProto.newBuilder();
            out.setC(code.value);
            out.setM(message);
            this.output = out.build();
        }

        // public ResultWrapper<DTO> As<DTO>()
        // {
        //     return this as ResultWrapper<DTO>;
        // }



    }