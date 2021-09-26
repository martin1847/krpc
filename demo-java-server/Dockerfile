FROM jcr.botaoyx.com/img/ubuntu:latest

ARG TARGET=.
# COPY ${TARGET}/build/*-runner ./application


COPY ${TARGET}/build/quarkus-artifact.properties./application
#

CMD ["./application"]
#, "-Dquarkus.http.host=0.0.0.0"]

