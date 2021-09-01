package com.bt;

import com.bt.demo.TimeReq;
import com.bt.demo.TimeResult;
import com.bt.demo.TimeService;
import com.bt.demo.service.HelloService;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets = {
        TimeReq.class,
        TimeResult.class,
        HelloService.class,
        TimeService.class

})
public class MyReflectionConfiguration {

}