package com.bt.rpc.common;

import com.bt.rpc.internal.OutputMessage;
import com.bt.rpc.model.Code;

public class ResultWrapper
    {

        public OutputMessage output;

        public ResultWrapper(){}

        public ResultWrapper(OutputMessage Output)
        {
            this.output = Output;
        }

        public ResultWrapper(Code code, String message)
        {
            var out = OutputMessage.newBuilder();
            out.setC(code.value);
            out.setM(message);
            this.output = out.build();
        }

        // public ResultWrapper<DTO> As<DTO>()
        // {
        //     return this as ResultWrapper<DTO>;
        // }



    }