package com.bt.rpc.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
//@Target(ElementType.) only mark Interface
public @interface RpcService {

    /// Default Name Trim the first `I` , for Example : IMyService -> MyService
    /// dot is Not Allowed here
    String value() default  "";

    /// maybe not use , use a whole nuget version instead ?
    String version()  default  "1.0.0";

    /// a longer description of this api
    String description() default  "";

    /// a cache prefix used in redis , default MethodDescriptor,FullName
    int expireSeconds() default -1;

}
