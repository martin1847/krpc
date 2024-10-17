/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2024 All Rights Reserved.
 */
package test.krpc.vt;

import sun.misc.Unsafe;

/**
 *
 * @author martin
 * @version 2024/08/31 12:11
 */
public class UnsafeUtil {

    public static Unsafe getUnsafe()  {
        //Field unsafeFi/eld = null;
        try {
            var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");

            //Field unsafeField = Unsafe.class.getDeclaredFields()[0];
            unsafeField.setAccessible(true);
            Unsafe unsafe =(Unsafe) unsafeField.get(null);
            return unsafe;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

}