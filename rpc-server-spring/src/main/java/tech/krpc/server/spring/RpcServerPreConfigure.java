/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package tech.krpc.server.spring;

import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tech.krpc.server.jws.ExtVerify;

/**
 *
 * @author martin
 * @version 2025/12/05 22:29
 */
@Configuration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE) // 低优先级
@ConditionalOnProperty(name = "rpc.server.app")
@Slf4j
public class RpcServerPreConfigure  {
    @Bean
    @ConditionalOnMissingBean( Validator.class )
    public EmptyValidator emptyValidator()  {
        log.debug("*******************【 USE EmptyValidator 】******************************* ");
        return new EmptyValidator();
    }

    @Bean
    @ConditionalOnMissingBean( ExtVerify.class )
    public EmptyExtVerify emptyExtVerify()  {
        log.debug("*******************【 USE EmptyExtVerify 】******************************* ");
        return new EmptyExtVerify();
    }

    @Bean
    public InitJwsVerify initJwsVerify()  {
        return new InitJwsVerify();
    }
}