package com.bt.rpc.server.ext;///**

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.validation.Validator;

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
        log.debug("Use validator {}", validator);
    }

}