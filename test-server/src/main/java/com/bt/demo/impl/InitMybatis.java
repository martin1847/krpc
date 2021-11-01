/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.demo.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.validation.Validator;

import io.quarkus.runtime.StartupEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 2:18 PM
 */
@ApplicationScoped
@Slf4j
public class InitMybatis {

    @Inject// by the ext
    SqlSessionFactory sqlSessionFactory;

    @Inject
    Validator validator;

    void onStart(@Observes StartupEvent ev) throws IOException, URISyntaxException {
        log.info("The application is starting  InitMybatis ..." + sqlSessionFactory);
        log.info("The application is starting  validator ..." + validator);

        //String path ="mapper/*.xml";
        //ClassLoader cl = Thread.currentThread().getContextClassLoader();
        //Enumeration<URL> resourceUrls = (cl != null ? cl.getResources(path) : ClassLoader.getSystemResources(path));
        //while (resourceUrls.hasMoreElements()) {
        //    URL url = resourceUrls.nextElement();
        //    System.out.println("Find URL : " + url);
        //}
        //
        //private List<File> getAllFilesFromResource(String folder)
        //throws URISyntaxException, IOException {

            //ClassLoader classLoader = getClass().getClassLoader();
            //
            //URL resource = classLoader.getResource("mapper");
            //
            //// dun walk the root path, we will walk all the classes
            // Files.walk(Paths.get(resource.toURI()))
            //        .filter(Files::isRegularFile)
            //        .map(x -> x.toFile().getPath())
            //        .forEach(it-> System.out.println("Got XML SQL File : " + it));

        //    return collect;
        //}

        //var configuration = sqlSessionFactory.getConfiguration();
        //
        //System.out.println(configuration);
        //var resource = "mapper/UserMapper.xml";
        //
        //
        //try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
        //    XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
        //    mapperParser.parse();
        //} catch (IOException e) {
        //    e.printStackTrace();
        //}
    }

}