package com.btyx.test.impl;///**

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.validation.Validator;

import com.bt.rpc.model.RpcResult;
import com.bt.rpc.util.JsonUtils;
import io.quarkus.runtime.StartupEvent;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 2:18 PM
 */
@ApplicationScoped
@Slf4j
public class ActiveValidator {

    @Inject
    Validator validator;

    void onStart(@Observes StartupEvent ev) {


        var res = RpcResult.ok(1);
        log.info("test json {}",JsonUtils.stringify(res));
        log.info("Enable validator {}" , validator);
    }

}