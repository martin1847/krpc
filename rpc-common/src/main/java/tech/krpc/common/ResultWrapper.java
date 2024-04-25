package tech.krpc.common;

import tech.krpc.internal.OutputProto;

public class ResultWrapper
    {

        public OutputProto output;

        public ResultWrapper(){}

        public ResultWrapper(OutputProto Output)
        {
            this.output = Output;
        }

        public ResultWrapper(int code, String message)
        {
            var out = OutputProto.newBuilder();
            out.setC(code);
            out.setM(message);
            this.output = out.build();
        }

        // public ResultWrapper<DTO> As<DTO>()
        // {
        //     return this as ResultWrapper<DTO>;
        // }



    }