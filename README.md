

# The `KRPC` Project

`KRPC` 是一个基于`面向所选编程语言`的快速开发框架（构建RPC服务），就像写一个普通函数一样。
* 序列化独立，默认采用json(最小成本兼容最多终端，android/iOS/h5/微信小程序等等)，不考虑跨语言，也可以使用特定语言序列化方案。
* 通信协议`HTTP/2`(plaintext)，代理友好、H5友好、移动设备友好，暴露给终端可经由网关配置`TLS`。

目前支持的语言如下：

| 语言     | 服务端 | 客户端 |
|--------|---| -------- |
| Java   | ✅ | ✅       |
| Rust   | ✅ | ✅       |
| C#     | ✅ | ✅       |
| Dart   | 否 | ✅       |
| js/web | 否 | ✅       |
| nodejs | 否  | ✅       |
| Python | 否 | ✅       |
| Go     |  ✅ | ✅       |


愿景：让`KRPC`成为现代网络`API`的事实标准（`rest`使命已完成，可以退出历史舞台了）。

why `KRPC`, `K`是什么：

* 首先,`K`代表`k8s`,云原生的含义
  * 全面拥抱`k8s`,拥抱`Service Mesh`服务网格,专业的事交给专业的人去做
  * 没有自己的服务发现，利用`k8s`的`service`[云原生服务发现](https://kubernetes.io/docs/concepts/services-networking/service)
  * 没有自己的负载均衡，利用`服务网格`[istio](https://istio.io/latest/docs/concepts/traffic-management/#load-balancing-options)
  * 没有自己的`telemetry`黄金四指标`four golden signals`,还是利用`服务网格的可观测性`[istio](https://istio.io/latest/zh/docs/concepts/observability/)
  * 没有自己实现web兼容`gRPC web`,还是利用[istio ingress](https://istio.io/latest/zh/docs/ops/configuration/traffic-management/protocol-selection/)
* `K`代表中文里的`kuai快`
  * 性能
    * 基于`Netty`和`gRPC`的极薄封装
    * java版本全面拥抱`GraalVM/quarkus`进行AOT(去除了反射)，适应云原生,启动秒起
    * CPU/内存占用低（经验：只有JVM版本的 20%～40%之间, AOT的好处，直接享受)
  * 开发
    * 无需知晓、编写`proto`,直接开发`Java interface`即可
    * 代码即文档，基于一套`interface`,生成`dart/typesctipt/python/go`等客户端，客户端所见即所得
    * 支持Flutter客户端(iOS、安卓原生客户端待测试)
    * 支持web客户端/微信小程序,通过`gRPC web`协议，（无需引入http/rest概念)
    
# 版本

不再支持`JDK8`，最小支持`JDK11`。

Latest version : `1.0.0`

```gradle
//implementation "tech.krpc:rpc-server:1.0.0"
//implementation "tech.krpc:rpc-client:1.0.0"
//implementation "tech.kext:ext-rpc:1.0.0"
```

![ARCHITECTURE](./ARCHITECTURE.png)

There is a  [demo-rpc](/example/demo-rpc) project. 

# 1. 声明接口和DTO (Data Transfer objects)

最重要的一步，API即文档，后续前端TypeScript、Java客户端均使用这一套API声明

* Just Add the  `rpc-api` and apply the `jandex` plugin for auto scan （quarkus编译阶段利用它扫描类进行AOT配置）.
* build.gradle file:

```gradle
plugins {
    id 'org.kordamp.gradle.jandex' version '2.0.0'
}

dependencies {
    api "tech.krpc:rpc-api:1.0.0"
}
```

for example :


```java
package com.xxxx;

@RpcService
public interface DemoService {

    RpcResult<HelloResult> hello(HelloReq req);

    RpcResult<byte[]> bytesTime();

    RpcResult<byte[]> incBytes(byte[] bytes);

    RpcResult<User> getUser(Integer id);

    RpcResult<PagedList<User>> listUser(PagedQuery<User> query);

    RpcResult<Integer> saveUser(User u);
}


@Data
public class HelloReq {

  @Doc("姓名")
  @NotBlank
  @Size(max = 10,message = "name's length too long than 10")
  private String name;

  @Doc("年龄")
  @NotNull
  @Min(1)@Max(80)
  private Integer age;
  
}

```

Convention & Limit  about the service define : 
* 字段使用 `jakarta.validation` 进行验证，默认采用 `hibernate-validator` 实现。
  * RPC框架自动验证，开发时标准好即可
* 代码即文档，请使用`Doc`加以说明字段含义
  * 使用框架自动生成前端`TypeScript`调用代码
  * `Doc`更是给前端、测试同学看的，请认真对待
* returnType must be `RpcResult<DTO>` .
    - `DTO` can be any object , BUT Abstract/Interface Not Support
    - Do not use Enum as return Field(Input can), maybe not Compatibility when Upgrade. Use string/int instead.
    - java`date/time`, jackson 情况下，会被转换为 [Unix Timestamp](https://en.wikipedia.org/wiki/Unix_time) (long type)
    - use customer `DTO` Object insteadOf simple object for Upgrade Friendly 
    - 除非必要，禁止使用Map做为出入参
    - 其余参考 [命名规范](https://redmine.btrpc.com/projects/bt/wiki/%E5%BC%80%E5%8F%91%E8%A7%84%E8%8C%83)
* 入参不多于一个 
* 标记 `RpcService` annotation


## 发布API到仓库

* 使用 [semver 版本](https://semver.org/lang/zh-CN/), 不要使用`SNAPSHOT`
* API定义可以使用java版本`11`预留兼容性 ， 其余业务代码可以使用 `java 17`
 
```gradle

apply from: "$rootProject.projectDir/gradle/upload.gradle"
// gradle clean publish 

    sourceCompatibility = "11"
    targetCompatibility = "11"
```
Then publish this API package to  https://jcr.btrpc.com  for the client side to reference.


# 2. Setup the Server

* 增加 [rpc-server] to your `build.gradle`

```gradle

implementation project(':your-api')

implementation "tech.krpc:rpc-server:1.0.0"
implementation "tech.krpc.ext:ext-rpc:1.0.0"

//implementation "tech.krpc.ext:ext-mybatis:1.0.0"
```
  
* Implention the service 
  
* Mark Service with   `@ApplicationScoped` and `@Startup `

```java
@ApplicationScoped
@Startup
public class DemoServiceImpl implements DemoService {
    @Override
    public RpcResult<TimeResult> hello(TimeReq req) {
        var res = new TimeResult();
        res.setTime(" from  (" + EnvUtils.hostName() + ") : " + req);
        res.setTimestamp(System.currentTimeMillis());
        return RpcResult.ok(res);
    }
}
```


# 3. Test in the Client Side

## 3.1 使用rpcurl

```bash
Usage: rpcurl https://demo.api.com/appName/Demo/methodName -f param.json
测试rpc服务

-L, --no-url          本机测试，本机测试 url=http://127.0.0.1:50051
-W, --no-web          测试非 UnsafeWeb 服务
-P, --no-pretty       NO pretty json
-h, --help            show usage
-u, --url             服务地址,默认参数,必传,也可通过环境变量`RPC_URL`传递,如: https://example.testapi.com/demo-java-server/Demo/hello
-a, --app             服务项目名,也可通过环境变量`RPC_APP`传递,如 demo-java-server
-s, --service         服务名
                      (defaults to "RpcMeta")
-m, --method          方法名
                      (defaults to "listApis")
-d, --data            入参json,优先级高于file,如 -d '{"name":"rpcurl"}'
-f, --file            入参jsonFile,如 -f test.json
-t, --token           authorization: Bearer <accessToken>,也可通过环境变量`RPC_TOKEN`传递
-i, --clientId        设置c-id,或者环境变量 `RPC_CID`
-M, --clientMeta      设置 c-meta(json),或者环境变量 `RPC_CMETA`
-V, --[no-]version    打印版本号 rpcurl-1.0 2022.02.24



# 比如
rpcurl https://example.testapi.com/demo-java-server/Demo/inc100 -d 90
rpcurl.exe https://example.testapi.com/demo-java-server/Demo/hello  -d '{"name":"rpc","age":123}' 
```

## 3.2 生成ts代码，前端调用测试

## 3.3 java项目测试


# Exception Handler

## 使用错误码/Soft Exception
* 约定错误码 ： https://redmine.btrpc.com/projects/bt/wiki/%E5%85%A8%E5%B1%80%E9%94%99%E8%AF%AF%E7%A0%81
* ServerSide : Just return a RpcResult with non-OK  Code and a error message(left data null)
* ClientSide : Check IsOk Before Use Data

## 禁止使用 Hard Exception。业务无关的除外，比如安全认证，参数校验，通常捕获为系统错误
* ServerSide : Throw a RpcException (Status/Runtime/Exception in Java Side) with your StatusCode or Other Exception(mapping to `Unknown` code)
* ClientSide : Get a RpcException (StatusRuntimeException Java side, catch it or not )


# 各种客户端

[Dart](https://gitlab.btrpc.com/middleware/zlkj-rpc-dart-client)

[TypeScript](https://gitlab.btrpc.com/middleware/zlkj-rpc-ts-client)

[Python](https://gitlab.btrpc.com/middleware/zlkj-rpc-python-client)

[go/k6](https://gitlab.btrpc.com/middleware/xk6-btrpc)

[rpcurl](./rpcurl/dart)

# CI & CD

[ ci demo](https://gitlab.btrpc.com/example/demo-rpc/-/pipelines)



