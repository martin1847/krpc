/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc.gen;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.common.MethodStub;
import com.bt.rpc.server.RpcServerBuilder;
import com.bt.rpc.server.RpcServerBuilder.RpcMetaMethod;
import com.bt.rpc.util.JsonUtils;
import com.bt.rpc.util.RefUtils;
import com.btyx.rpc.gen.meta.ApiMetaRoot;
import com.btyx.rpc.gen.meta.Dto;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 4:32 PM
 */
public class Gen {


    public static String basePkg ="com.btyx";

    //static final String FC = File.separator;

    static final String[] CLS_FOLDERS ={ "/out/test/classes/","/build/classes/java/"};

    public static File defaultFolder(LangEnum lan){
        var url = Gen.class.getResource("/");
        if(null == url){
            return null;
        }
        var path = url.getPath();
        for (var suf : CLS_FOLDERS) {
            int i;
            if ( (i =path.lastIndexOf(suf)) > 0) {
                var f = new File(path.substring(0, i));
                var subLang = new File(f, lan.name().toLowerCase(Locale.US));
                System.out.println("use folder : " + subLang);
                subLang.mkdir();
                return subLang;
            }
        }
        System.out.println("unknow path : "+ path);
        return null;
    }

    public static void genTypescript(String appName){
        genTypescript(appName,defaultFolder(LangEnum.Typescript));
    }

    public static void genMiniprogram(String appName){
        gen(appName,LangEnum.Miniprogram,defaultFolder(LangEnum.Miniprogram));
    }

    public static void genDart(String appName){
        genDart(appName,defaultFolder(LangEnum.Dart));
    }

    public static void genYamltest(String appName){
        try {
            gen(appName,LangEnum.Yamltest,defaultFolder(LangEnum.Yamltest));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void genTypescript(String appName, File outFolder) {
        try {
            gen(appName,LangEnum.Typescript,outFolder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void genDart(String appName, File outFolder){
        try {
            gen(appName,LangEnum.Dart,outFolder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    static void gen(String appName, LangEnum template, File outFolder)  {

        //Set<ClassInfo> classesInPackage = ClassPath.from(cl).getTopLevelClassesRecursive("com.btyx");
        //classesInPackage.forEach(it->
        //
        //        System.out.println(it.load()));
        ApiMetaRoot metas ;
        try {
            metas = scan(appName,basePkg);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        /* Create and adjust the configuration singleton */
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setClassForTemplateLoading(Gen.class,"/");
        //cfg.setDirectoryForTemplateLoading(new File("/where/you/store/templates"));
        // Recommended settings for new projects:
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);

        /* Create a data-model */
        var root = new HashMap<String,Object>();
        root.put("app", metas.getApp());
        root.put("lang", template.name());
        var dtos = metas.getDtos().stream().filter(Dto::hasChild).collect(Collectors.toList());
        //JSON dtos;
        dtos.forEach(template.remapping::remapping);
        root.put("dtos",dtos );

        Template dtoTemp ;
        try {
            dtoTemp = cfg.getTemplate(template.dto);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var dtoFileName = template.dtoFileName(metas.getApp());

        root.put("dtoFile",dtoFileName );
        System.out.println("---------- gen to : "+ outFolder);
        System.out.println("---------- gen : "+ dtoFileName);

        try {
            dtoTemp.process(root, toWriter(outFolder,dtoFileName));
            Template serviceTemp = cfg.getTemplate(template.serive);
            for (var api :metas.getApis()) {
                root.put("service",api );
                api.getMethods().forEach(m->{
                    template.remapping.remapping(m.getArg());
                    template.remapping.remapping(m.getRes());
                });
                Collections.sort(api.getMethods());
                var serviceFile = template.serviceFileName(api.getName());
                root.put("serviceFile",serviceFile );
                System.out.println("---------- gen : "+ serviceFile);
                serviceTemp.process(root, toWriter(outFolder,serviceFile));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    static Writer toWriter(File outFolder,String fileName) throws IOException {
        if(null == outFolder){
            return new OutputStreamWriter(System.out);
        }
        var out = new File(outFolder,fileName);
        //out.getParentFile().mkdirs();
        return new FileWriter(out, StandardCharsets.UTF_8);
    }

    static ApiMetaRoot scan(String appName , String pkg) throws IOException {
        Set<ClassInfo> classesInPackage = ClassPath.from(Thread.currentThread().getContextClassLoader())
                .getTopLevelClassesRecursive(pkg);

        var metaMethods = new ArrayList<RpcMetaMethod>();
        for(var ci : classesInPackage){
            var clz  = ci.load();

            if(clz.isInterface() && clz.isAnnotationPresent(UnsafeWeb.class)){
                var rpcAnno = clz.getAnnotation(RpcService.class);
                System.out.println("---------- Found RpcService : " + clz.getName() );
                for(MethodStub stub : RefUtils.toRpcMethods(appName,clz)){
                    metaMethods.add(RpcServerBuilder.toMeta(stub,rpcAnno));
                }
            }
        }

        var api = RpcServerBuilder.buildApiMeta(metaMethods);
        api.setApp(appName);
        var json = JsonUtils.stringify(api);

        return JsonUtils.parse(json,ApiMetaRoot.class);
    }

}