
Latest version : https://jcr.botaoyx.com/ui/artifactSearchResults?name=rpc&type=artifacts
```xml
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

    @Size(max = 10,message = "name's length too long than 10")
    private String name;
    private int age;
}


```

Convention & Limit  about the service define : 

* returnType must be `RpcResult<Data>` .
    - `Data` can be any object , BUT Abstract/Interface Not Support
    - Do not use Enum as return Field(Input can), maybe not Compatibility when Upgrade. Use string/int instead.
    - date/time use [Unix Timestamp](https://en.wikipedia.org/wiki/Unix_time) (long type, language and Timezone  independent)
    - use customer `DTO` Object insteadOf simple object for Upgrade Friendly 
    - use List<Item> insteadOf Dictionary
    - Csharp Side use a `Task` as wrap : `Task<RpcResult>`, Java side no need
* number of parameters at most 1 
* Remember add the `RpcService` annotation, then the SDK will recognition it as a Rpc Service.
* Streaming Method Not Support Current
  
Then publish this API package to  https://jcr.botaoyx.com  for the client side to reference.


# 2. Setup the Server

* Add the ProjectReference [rpc-server] to your csproj

```xml
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



# Exception Handler

## Soft Exception, Most case recommend .
* 约定错误码 ： https://redmine.botaoyx.com/projects/bt/wiki/%E5%85%A8%E5%B1%80%E9%94%99%E8%AF%AF%E7%A0%81
* ServerSide : Just return a RpcResult with non-OK  Code and a error message(left data null)
* ClientSide : Check IsOk Before Use Data

## 禁止使用 Hard Exception ,通常捕获为系统错误
* ServerSide : Throw a RpcException (Status/Runtime/Exception in Java Side) with your StatusCode or Other Exception(mapping to `Unknown` code)
* ClientSide : Get a RpcException (StatusRuntimeException Java side, catch it or not )


# CI & CD

[ ci demo](https://gitlab.botaoyx.com/example/demo-rpc/-/pipelines)





# Benefits and Weaknesses

## Benefits 


* Loose coupling between clients/server makes changes easy
* Easy for testing, inspection, and modification
* Significant Transport performance benefits over HTTP rest
* Developer Friendly 
    - Easy to understand, strict specification
    - deal with interface/poco , easy than http request
    - Eliminates debate and saves time to the best format of URLs/HTTP verbs/response codes.
    - Developer performence improve , No need to write client libraries
* Non Function Requirments  Built-in 
    - Deadline/timeouts and cancellation for resource usage limits
    - Distributed tracing( W3C over jager)
    - Monitor / Metrics Telemetry
    - Model Validation with DataAnnotations
    - Extensibility function, for example API Cache etc.

## Weaknesses

* Limited browser support
* Not human readable

## Recommended scenarios

![decomposing](./decomposing.png)

* Microservices –  especially only internal and when one server needs to talk to the other.
* decomposing monolithic apps
* Network constrained environments – Power saving on mobile 
* Polyglot environments - both java and csharp
* When you don’t feel to write client libraries.
