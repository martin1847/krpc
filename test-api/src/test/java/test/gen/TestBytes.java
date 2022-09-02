/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.gen;

import java.util.Arrays;
import java.util.Date;

import com.bt.rpc.util.JsonUtils;
import com.btyx.test.dto.Img;
import com.btyx.test.dto.TimeResult;

/**
 *
 * @author Martin.C
 * @version 2022/01/11 5:47 PM
 */
public class TestBytes {

    public static void main(String[] args) {

        var res = new TimeResult();
        res.setDate(new Date());
        System.out.println(JsonUtils.stringify(res));

        var json = "{\"name\":\"123\",\"img\":[1,2,3,4]}";

        var img = JsonUtils.parse(json, Img.class);
        System.out.println(
                Arrays.toString(img.getImg())
        );

        var arrs = Arrays.asList("pvc-59bf2267-2604-4a24-ba57-32be8a1504e6",
                "pvc-670342e4-52d1-4fc9-a873-85184d9a0fff",
                "pvc-8ea6264e-8533-4867-82a6-a1155c3bdccf",
                "pvc-edb529a0-ebe1-4a51-84db-7e25fecd7ab4",
                "pvc-1659c637-31d6-4c7a-bb7e-367fa7f77a99",
                "pvc-8d049352-7793-413c-abe3-86b70d4b28bc",
                "pvc-94b79e52-e1e6-4b05-862a-34e1af250c10",
                "pvc-13a3b5b0-76e1-43dd-a15a-bda401722a2d");

        var pods = Arrays.asList("pvc-10c1c499-5b13-45e2-85f5-97abc9dfcebf-target-687f68f55c4mrlw",
                "pvc-13a3b5b0-76e1-43dd-a15a-bda401722a2d-target-7756bb669fzjl9c",
                "pvc-1659c637-31d6-4c7a-bb7e-367fa7f77a99-target-66f75548954tf4r",
                "pvc-1f15ae0c-af7b-4014-8412-232a70f22d64-target-6f5b8cf6cfvct4f",
                "pvc-2024b799-673f-4718-aa10-b16d0fe9e956-target-6944846d9dlh7c8",
                "pvc-2586c05f-d007-41ee-a32f-4bb935873483-target-c6f9b865d-89jv4",
                "pvc-44ff339c-890a-4ed0-aebe-7e2b29752b46-target-64db7c66f9gjlcs",
                "pvc-46147199-fe0f-4f98-b47f-7dfac9063524-target-795dbbf7b4zksxq",
                "pvc-49422034-9c64-4dcf-a98a-649839325d77-target-9b6b69fdc-phwls",
                "pvc-4da3a3ba-26d6-404b-bb0d-d2f1c8542a52-target-7f97d97c77s6258",
                "pvc-59bf2267-2604-4a24-ba57-32be8a1504e6-target-669c597966xp5pg",
                "pvc-639ee63e-f592-4d5a-8a8a-3ad81037ec6b-target-5b876ddc9dnbwr7",
                "pvc-642cd094-7b6c-4e47-8fff-f078b56aa555-target-569666d8b5jqmkx",
                "pvc-670342e4-52d1-4fc9-a873-85184d9a0fff-target-7b786d58c9rdnwt",
                "pvc-78dad503-b3ac-4e82-9675-a62c69130f40-target-7d7779b9d4k5rvm",
                "pvc-7a2574bc-ec33-457f-bc8b-31d3c20ffec5-target-755474b868np6zx",
                "pvc-7ae34dcf-7710-4f0f-8d69-0c6c5f936a25-target-5ddd9d8f6-wg9hz",
                "pvc-7d333a43-00df-40f3-bb92-b12942b0655b-target-8498755d64x672g",
                "pvc-7e3953dd-a41e-4fcc-b74a-4199981e69be-target-6df57796f9pz4mz",
                "pvc-7f2c02f8-6bc1-4e75-a313-117c2198836f-target-7596d56d5fs4vr8",
                "pvc-8d049352-7793-413c-abe3-86b70d4b28bc-target-599846b4b82gnfm",
                "pvc-8ea6264e-8533-4867-82a6-a1155c3bdccf-target-6748c5ffc66dfv7",
                "pvc-94b79e52-e1e6-4b05-862a-34e1af250c10-target-7bfb467bc6vmvjd",
                "pvc-9dc468b3-5377-4f41-be51-794e18443c94-target-5964b844d75jzpf",
                "pvc-9e4a16de-252b-4ba1-a347-2c722b64e9d6-target-755478fc4cctmsx",
                "pvc-9e83faf0-a017-45a9-b8a2-f60c4b477738-target-779b86d848jr5zn",
                "pvc-a0fec112-b7d0-4e44-add5-977d57abba69-target-7858cbc948vq7bz",
                "pvc-a68a9e73-deb5-4534-ae62-d97927a9c3d0-target-86d6b65b475h8mf",
                "pvc-a95e8901-ad46-4308-ac60-03509648169f-target-5d7f66966fvbf6x",
                "pvc-b25f56c7-f14b-4be1-861a-d441f4f4e638-target-5459bc879d2s54b",
                "pvc-b80dfbae-fa1c-4ae0-8f5d-eb854db4023d-target-7b9c9597d6ngx6x",
                "pvc-bfaf43b5-fc38-41a7-8729-c26d0b4115b7-target-7dc74756d5j74rr",
                "pvc-dbd81a72-2758-4930-a011-c6d8b0f17150-target-844c44b984k7dvn",
                "pvc-dcc907b1-679a-4740-8def-0c36feb80641-target-65787bfc5-xdh6j",
                "pvc-ea7bb114-814d-4629-9ffd-2a8092780a70-target-74f76f996f74qqk",
                "pvc-edb529a0-ebe1-4a51-84db-7e25fecd7ab4-target-69dc847df5678g8",
                "pvc-ede85f99-5ce7-48ed-a532-52189fa48ea9-target-85d46d8fd72x2m8",
                "pvc-fd304743-b148-4b37-838f-51048862556d-target-866fd6f9dcdx8qf");

        for (var pod : pods){
            if(arrs.stream().noneMatch(pod::startsWith)){
                System.out.println("k delete pod "+pod);
            }
        }
    }

}