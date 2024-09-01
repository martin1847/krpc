/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.http.server;

import io.netty.util.AsciiString;

/**
 *
 * @author Martin.C
 * @version 2021/11/16 3:14 PM
 */
public class AsciiHeader {

    public final AsciiString name;

    public final String value;


    public AsciiHeader(AsciiString name, String value) {
        this.name = name;
        this.value = value;
    }

}