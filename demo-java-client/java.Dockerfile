FROM docker-local.jfrog.io/cibuildslave/java:11
ARG TARGET=.
# COPY ${TARGET}/build/quarkus-app/* ./

COPY --chown=1000 ${TARGET}/build/quarkus-app/lib/ ./lib/
COPY --chown=1000 ${TARGET}/build/quarkus-app/*.jar ./app.jar
COPY --chown=1000 ${TARGET}/build/quarkus-app/app/ ./app/
COPY --chown=1000 ${TARGET}/build/quarkus-app/quarkus/ ./quarkus/

# echo "securerandom.source=file:/dev/urandom" >> /etc/alternatives/jre/conf/security/java.security

# vm system all
CMD java ${JAVA_OPT} -XshowSettings:all -Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager -jar app.jar

#  -Dquarkus.log.level=all

# Then, build the image with:
#
# docker build -f src/main/docker/Dockerfile.jvm -t quarkus/code-with-quarkus-jvm .
#
# Then run the container using:
#
# docker run -i --rm -p 8080:8080 quarkus/code-with-quarkus-jvm
#
# If you want to include the debug port into your docker image
# you will have to expose the debug port (default 5005) like this :  EXPOSE 8080 5005
#
# Then run the container using :
#
# docker run -i --rm -p 8080:8080 -p 5005:5005 -e JAVA_ENABLE_DEBUG="true" quarkus/code-with-quarkus-jvm
#
###

# Second stage, add only our minimal "JRE" distr and our app
# FROM debian:stretch-slim
#
#
# # Java 9 (and later) include a tool called jlink.
# # With jlink, we can build a custom JVM, with only the components that we need.
# FROM openjdk:15-alpine
# RUN apk add binutils # for objcopy, needed by jlink
# COPY hello.java .
# RUN javac hello.java
# RUN jdeps --print-module-deps hello.class > java.modules
# RUN jlink --strip-debug --add-modules $(cat java.modules) --output /java
#
# FROM alpine
# COPY --from=0 /java /java
# COPY --from=0 hello.class .
# CMD exec /java/bin/java -cp . hello

