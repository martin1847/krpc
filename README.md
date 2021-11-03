
Latest version : https://jcr.botaoyx.com/ui/artifactSearchResults?name=rpc&type=artifacts
```gradle
implementation "com.bt.rpc:rpc-server:1.0.0"
implementation "com.bt.ext:ext-rpc:1.0.1"
```

![ARCHITECTURE](./ARCHITECTURE.png)

# How To RPC  , Code First , Without Proto Contract 

Base on the [offical rpc SDK](https://github.com/rpc/rpc-dotnet):

* The Main Feauter is added that we can write Interface first , Without Proto Contract.
* This is Just Like a Local Nuget Service Invoke , but the service in effect is remote.
* The Server side  can be either Csharp or Java , both has  same Behavior.

There is a  [demo-rpc](/example/demo-rpc) project. 

# 1. Declar you Interface  and DTO (Data Transfer objects)


* Just Add the  `rpc-api` and apply the `jandex` plugin for auto scan.
* build.gradle file:


```gradle

plugins {
    id 'org.kordamp.gradle.jandex' version '0.11.0'
}

dependencies {
    api "com.bt.rpc:rpc-api:1.0.0"
}
```

for example :


```java
package com.btyx.course.xxxx;

@RpcService(description = "this is a Java test service")
public interface DemoService {

//    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<byte[]> bytesTime();

    RpcResult<byte[]> incBytes(byte[] bytes);

    RpcResult<User> getUser(Integer id);

    RpcResult<PagedList<User>> listUser(PagedQuery<User> query);

    RpcResult<Integer> saveUser(User u);
}


@Data
@AllArgsConstructor
@NoArgsConstructor
public class TimeReq {

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
* 字段使用 `javax.validation` 进行验证，默认采用 `hibernate-validator` 实现。
  * RPC框架自动验证，开发时标准好即可
* API即文档，请使用`Doc`加以说明字段含义
  * 使用框架自动生成前端`TypeScript`调用代码
  * `Doc`更是给前端、测试同学看的，请认真对待
* returnType must be `RpcResult<DTO>` .
    - `DTO` can be any object , BUT Abstract/Interface Not Support
    - Do not use Enum as return Field(Input can), maybe not Compatibility when Upgrade. Use string/int instead.
    - 不要直接使用`date/time`, use [Unix Timestamp](https://en.wikipedia.org/wiki/Unix_time) (long type)
    - use customer `DTO` Object insteadOf simple object for Upgrade Friendly 
    - 除非必要，禁止使用Map做为出入参
    - 其余参考 [命名规范](https://redmine.botaoyx.com/projects/bt/wiki/%E5%BC%80%E5%8F%91%E8%A7%84%E8%8C%83)
* 入参不多于一个 
* 标记 `RpcService` annotation

Then publish this API package to  https://jcr.botaoyx.com  for the client side to reference.


# 2. Setup the Server

* Add the ProjectReference [rpc-server] to your `build.gradle`

```gradle
implementation "com.bt.rpc:rpc-server:1.0.0"
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

## 3.1 生成ts代码，前端调用测试

## 3.2 java项目测试


# Exception Handler

## 使用错误码/Soft Exception
* 约定错误码 ： https://redmine.botaoyx.com/projects/bt/wiki/%E5%85%A8%E5%B1%80%E9%94%99%E8%AF%AF%E7%A0%81
* ServerSide : Just return a RpcResult with non-OK  Code and a error message(left data null)
* ClientSide : Check IsOk Before Use Data

## 禁止使用 Hard Exception ,通常捕获为系统错误
* ServerSide : Throw a RpcException (Status/Runtime/Exception in Java Side) with your StatusCode or Other Exception(mapping to `Unknown` code)
* ClientSide : Get a RpcException (StatusRuntimeException Java side, catch it or not )


# CI & CD

[ ci demo](https://gitlab.botaoyx.com/example/demo-rpc/-/pipelines)



