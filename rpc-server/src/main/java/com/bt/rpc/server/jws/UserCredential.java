/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import java.util.List;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 2:38 PM
 */
public interface UserCredential {

    String getAlgorithm();

    String getKeyId();

    String getIssuer();

    /**
     * Get the value of the "sub" claim, or null if it's not available.
     *
     * @return the Subject value or null.
     */
    String getSubject();

    /**
     * Get the value of the "aud" claim, or null if it's not available.
     *
     * @return the Audience value or null.
     */
    List<String> getAudience();

    /**
     * Get the value of the "exp" claim, or null if it's not available.
     * EpochSecond
     */
    Number getExpiresAt();

    /**
     * Get the value of the "nbf" claim, or null if it's not available.
     *
     * @return EpochSecond or null.
     */
    Number getNotBefore();

    /**
     * Get the value of the "iat" claim, or null if it's not available.
     *
     * @return EpochSecond  or null.
     */
    Number getIssuedAt();

    /**
     * Get the value of the "jti" claim, or null if it's not available.
     *
     * @return the JWT ID value or null.
     */
    String getId();

    Object get(String key);
}