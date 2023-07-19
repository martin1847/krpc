/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.http.server;

/**
 *
 * @author Martin.C
 * @version 2021/12/22 9:24 AM
 */
public interface PostHandler<ParamDTO> extends Handler<ParamDTO> {

    Class<ParamDTO> getParamClass();

    default boolean useValidator() {
        return true;
    }

}