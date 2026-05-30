# KRPC

[English](README.md)

KRPC 是一个面向云原生服务的 interface-first RPC 框架。

写一个 Java interface，把它作为 API 契约发布，KRPC 负责 RPC 传输、参数校验、元数据和客户端生成。普通业务 API 不需要手写 proto 文件。

## 它解决什么

- 使用 gRPC / HTTP/2 作为传输协议。
- 默认使用 JSON，方便覆盖前端、移动端、脚本和服务间调用。
- 以 Java interface 和 DTO 作为 API source of truth。
- 支持生成多端客户端。
- 支持 JDK 21 和 virtual threads。
- 拥抱 Kubernetes 和 service mesh，不重复造基础设施。

KRPC 不负责服务发现、负载均衡、可观测性、入口 TLS 或 mesh 策略。这些交给 Kubernetes、Istio、gateway 或部署平台。

KRPC 已用于电商、教育、本地生活等生产场景。

## 模块

- `rpc-api`：注解和共享 API 模型。
- `rpc-common`：序列化、上下文、过滤器、元数据和工具类。
- `rpc-client`：Java 客户端运行时。
- `rpc-server`：Java 服务端运行时。
- `rpc-client-spring`：Spring 客户端集成。
- `rpc-server-spring`：Spring 服务端集成。
- `rpc-server-quarkus`：Quarkus 和 native-image 集成。
- `http-server`：HTTP gateway 支持。
- `test-rpc-gen`：客户端代码生成示例。
- `rpcurl`：命令行 RPC 客户端。

![Architecture](./ARCHITECTURE.png)

## 环境要求

- JDK 21
- Gradle

当前版本：`1.0.0`

```gradle
implementation "tech.krpc:rpc-api:1.0.0"
implementation "tech.krpc:rpc-client:1.0.0"
implementation "tech.krpc:rpc-server:1.0.0"
```

## 定义 API

在 API 模块中引入 `rpc-api`：

```gradle
plugins {
    id "org.kordamp.gradle.jandex" version "2.0.0"
}

dependencies {
    api "tech.krpc:rpc-api:1.0.0"
}
```

用 Java interface 定义服务：

```java
@RpcService
public interface DemoService {
    RpcResult<HelloResult> hello(HelloReq req);
}

public class HelloReq {
    @Doc("name")
    @NotBlank
    private String name;
}
```

API 规则：

- 返回值使用 `RpcResult<DTO>`。
- 每个方法只使用一个入参对象。
- 请求和响应都使用 DTO。
- 使用 `jakarta.validation` 做参数校验。
- 需要生成客户端文档的字段加 `@Doc`。
- 除非有强理由，API 契约里避免使用 `Map`。
- 响应 DTO 中尽量避免 enum 字段，降低客户端长期演进成本。

共享 API 包使用语义化版本发布，避免使用 `SNAPSHOT`。

## 实现服务端

引入 API 和 server runtime：

```gradle
dependencies {
    implementation project(":your-api")
    implementation "tech.krpc:rpc-server:1.0.0"
}
```

实现 interface：

```java
@ApplicationScoped
@Startup
public class DemoServiceImpl implements DemoService {
    @Override
    public RpcResult<HelloResult> hello(HelloReq req) {
        return RpcResult.ok(new HelloResult("hello " + req.getName()));
    }
}
```

## 使用 rpcurl 调用

`rpcurl` 可以从 [martin1847/krpc-crates](https://github.com/martin1847/krpc-crates/) 获取。

```bash
export KRPC_APP="https://example.com/demo"

rpcurl "$KRPC_APP/Demo/hello" -d '{"name":"krpc"}'
```

常用参数：

```text
-d, --data <DATA>      请求 JSON
-f, --file <FILE>      请求 JSON 文件
-t, --token <TOKEN>    Authorization: Bearer token
-c, --cookie <COOKIE>  Cookie header
-H, --header <HEADER>  自定义 header，例如 -H a=b
-v, --verbose          输出详细信息
```

## 错误处理

业务失败使用 soft error：

- 服务端：返回非 OK code 和 message 的 `RpcResult`。
- 客户端：读取 data 前先检查 `isOk()`。

系统错误、安全失败、参数校验失败和非预期运行时错误使用异常。

## 运行现有 Demo

仓库里已有一个集成 demo：`test-api` + `test-server`。

构建：

```bash
gradle :test-server:build -x test
```

启动：

```bash
gradle :test-server:quarkusDev \
  -Dquarkus.datasource.password=youshallnotpass \
  -Ddebug=false \
  --console=plain
```

调用：

```bash
rpcurl http://127.0.0.1:50051/test-server/Demo/hello \
  -d '{"name":"krpc","age":18}'
```

这是集成 demo，不是最小 quickstart 模板。它包含 MySQL、MyBatis 和 JWKS 相关配置；本地无网络时可能出现 JWKS 拉取 warning，但不影响 `Demo/hello` 调用。

## 客户端

已支持或配套支持 Dart、TypeScript、Python、Go/k6、Java 和 rpcurl。

## 项目治理

- 文档入口：[docs/INDEX.md](docs/INDEX.md)
- 仓库边界：[ADR-0001](docs/decisions/ADR-0001-repository-scope.md)
- JDK 21 和 virtual threads：[ADR-0002](docs/decisions/ADR-0002-jdk21-virtual-threads.md)
