FROM jcr.botaoyx.com/img/common/java:17
ARG TARGET=.


# COPY --chown=1001 ${TARGET}/build/quarkus-app/lib/ .
# COPY --chown=1001 ${TARGET}/build/quarkus-app/*.jar ./app.jar
# COPY --chown=1001 ${TARGET}/build/quarkus-app/app/ .
# COPY --chown=1001 ${TARGET}/build/quarkus-app/quarkus/ .

COPY --chown=1001 ${TARGET}/build/quarkus-app/ .
#  -XshowSettings:all
CMD java ${JAVA_OPT} ${EXT_OPT} -jar quarkus-run.jar

#  -Dquarkus.log.level=all

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

