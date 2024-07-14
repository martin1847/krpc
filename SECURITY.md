# Security Policy

* `kRPC`默认使用`gRPC over plaintext`模式，只处理东西向流量，不直接对外提供服务。

* `TLS`放在网关层，通过[ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/)(Envoy/Nginx)暴露。

## 核心依赖组件安全声明

* [gRPC Security Policy](https://github.com/grpc/grpc-java/blob/master/SECURITY.md)
* [gRPC CVE Process](https://github.com/grpc/proposal/blob/master/P4-grpc-cve-process.md)
* [Netty Security Policy](https://github.com/netty/netty?tab=security-ov-file)

