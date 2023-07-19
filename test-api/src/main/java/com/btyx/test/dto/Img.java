/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.btyx.test.dto;

import java.util.List;
import java.util.Map;

import javax.validation.constraints.Size;

import com.bt.rpc.annotation.Doc;
import lombok.Data;

/**
 *
 * @author Martin.C
 * @version 2022/01/11 5:47 PM
 */
@Data
public class Img {


    @Doc("新增为null， 编辑不可空")
    @Size(min = 1)
    String id;

    @Doc("为null表示删除掉,最大100kb")
    @Size(min = 1024,max = 1024*100)
    byte[] img;


    @Doc("测试int[]数组")
    @Size(min = 1024,max = 1024*100)
    int[] intArray;



    @Doc("测试List<int>")
    @Size(min = 1,max = 100)
    List<Integer> intList;

    @Doc("测试Map<String,String>")
    Map<String,String> strMap;


}