FROM jcr.botaoyx.com/img/common/ubi:7.9

ARG TARGET=.
COPY ${TARGET}/build/*-runner ./rpcurl
# CMD [" default param here "]
ENTRYPOINT ["./rpcurl"]