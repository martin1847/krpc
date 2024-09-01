# FROM jcr.btrpc.com/img/common/ubi:7.9
FROM frolvlad/alpine-glibc

ARG TARGET=.
COPY ${TARGET}/build/*-runner ./rpcurl
CMD ["-h"]
ENTRYPOINT ["./rpcurl"]