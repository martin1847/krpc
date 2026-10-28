/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.jws;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 11:31 AM
 */
@Data
public class Jwks {

    public static final String KEY_TYPE = "kty";

    // https://tools.ietf.org/id/draft-ietf-jose-json-web-key-01.html
    //
    // Map<String,Object>, not Map<String,String>: RFC 7517 §4 puts no type constraint on JWK
    // members, so a vendor extension may legitimately be a number, boolean, array or object
    // (e.g. `"x5c": [...]`, `"exp": 1730000000`). Typing the value as String made every such
    // document depend on Jackson silently stringifying the scalar; under the strict decoding
    // that is now the default (1.2.0) it would have failed the entire keyset instead.
    // JwsVerify reads only the members it consumes, and skips a JWK whose consumed members are
    // not strings rather than rejecting the whole document.
    List<Map<String,Object>> keys;
}