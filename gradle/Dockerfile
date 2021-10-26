# FROM jcr.botaoyx.com/img/common/ubuntu:21
FROM jcr.botaoyx.com/img/common/ubi:7.9

ARG TARGET=.
COPY ${TARGET}/build/*-runner ./app
CMD ["./app"]