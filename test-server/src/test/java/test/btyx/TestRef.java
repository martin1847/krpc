/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.btyx;

import com.bt.rpc.util.RefUtils;
import com.testbt.dto.Img;
import com.testbt.dto.SubImg;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Martin.C
 * @version 2022/09/02 1:13 PM
 */
public class TestRef {


    @Test
    void  testAnno(){
        System.out.println(RefUtils.needValidator(Img.class));
        System.out.println(RefUtils.needValidator(SubImg.class));
    }


}