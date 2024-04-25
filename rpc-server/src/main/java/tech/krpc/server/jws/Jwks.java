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
    List<Map<String,String>> keys;
}