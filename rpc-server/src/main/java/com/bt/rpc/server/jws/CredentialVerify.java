/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import java.util.regex.Pattern;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.StatusException;

import static com.bt.rpc.server.jws.HttpConst.BEARER_FLAG;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 1:52 PM
 */
//@FunctionalInterface
public interface CredentialVerify {

    UserCredential verify(String token,String cid,boolean isCookie) throws StatusException;

    String getCookieName();

    Pattern COOKIE_SPLIT = Pattern.compile(";\\s*");
    Key<String> COOKIE        = Metadata.Key.of(HttpConst.COOKIE_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> AUTHORIZATION = Metadata.Key.of(HttpConst.AUTHORIZATION_HEADER, Metadata.ASCII_STRING_MARSHALLER);


    int TOKEN_INDEX = BEARER_FLAG.length() + 1;

    static String bearerToken(Metadata headers) {
        var tokenPlace = headers.get(AUTHORIZATION);
        String token = null;
        if (null != tokenPlace
                && tokenPlace.length() > TOKEN_INDEX
                && tokenPlace.startsWith(BEARER_FLAG)) {
            token = tokenPlace.substring(TOKEN_INDEX);
        }
        return token;
    }

    default String cookieToken(Metadata headers){
        var tokenPlace = headers.get(COOKIE);
        String token = null;
        if (tokenPlace != null) {
            var cookies = COOKIE_SPLIT.split(tokenPlace);
            final String cookiePrefix = getCookieName() + '=';
            for (var ck : cookies) {
                if (ck.startsWith(cookiePrefix)) {
                    token = ck.substring(cookiePrefix.length());
                    break;
                }
            }
        }
        return token;
    }

}