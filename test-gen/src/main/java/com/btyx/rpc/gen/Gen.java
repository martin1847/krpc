/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc.gen;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 4:32 PM
 */
public class Gen {



    public static void genTypescript(String appName, File outFolder) throws IOException, TemplateException {
        gen(appName,LangEnum.Typescript,outFolder);
    }

    public static void genDart(String appName, File outFolder) throws IOException, TemplateException {
        gen(appName,LangEnum.Dart,outFolder);
    }
    static void gen(String appName, LangEnum template, File outFolder) throws IOException, TemplateException {

        //Set<ClassInfo> classesInPackage = ClassPath.from(cl).getTopLevelClassesRecursive("com.btyx");
        //classesInPackage.forEach(it->
        //
        //        System.out.println(it.load()));

        var metas =  scan(appName,"com");

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
        Map root = new HashMap();
        root.put("app", metas.getApp());
        var dtos = metas.getDtos().stream().filter(Dto::hasChild).collect(Collectors.toList());
        //JSON dtos;
        dtos.forEach(template.remapping::remapping);
        root.put("dtos",dtos );

        /* Get the template (uses cache internally) */
        Template dtoTemp = cfg.getTemplate(template.dto);
        /* Merge data-model with template */
        Writer out = new OutputStreamWriter(System.out);
        dtoTemp.process(root, out);

        Template serviceTemp = cfg.getTemplate(template.serive);
        for (var api :metas.getApis()) {
            root.put("service",api );
            api.getMethods().forEach(m->{
                template.remapping.remapping(m.getArg());
                template.remapping.remapping(m.getRes());
            });
            /* Merge data-model with template */
            out = new OutputStreamWriter(System.out);
            serviceTemp.process(root, out);
        }
        //System.out.println(out);
        //System.out.println(JsonUtils.stringify(metas.getDtos()));
    }


    static ApiMetaRoot scan(String appName , String pkg) throws IOException {
        Set<ClassInfo> classesInPackage = ClassPath.from(Thread.currentThread().getContextClassLoader())
                .getTopLevelClassesRecursive(pkg);

        var metaMethods = new ArrayList<RpcMetaMethod>();
        for(var ci : classesInPackage){
            var clz  = ci.load();

            if(clz.isInterface() && clz.isAnnotationPresent(UnsafeWeb.class)){
                var rpcAnno = clz.getAnnotation(RpcService.class);
                System.out.println("Found RpcService : " + clz.getName() );
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