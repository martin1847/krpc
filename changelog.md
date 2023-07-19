
# 1.0.0 , 2023-05-05

* `javax.` -> `jakarta.`
* quarkus -> 3.0
* grpc -> 1.54.1

# 1.0.0 , 2020-12-02

* add client final message support

2020-07-21 GLS , publish online.

# 1.0.0 , 2020-06-30

* .net2.0 client publish

# 1.0.0 , 2020-06-20

* Dictionary key keep same , not camel



# 1.0.0 , 2020-05-06

* remove  Google.Api.CommonProtos
* shortter Property name of Outmessage


# 1.0.0 , 2020-04-24

* ci/cd ok



# 1.0.0-rc , 2020-04-20

* appsettings.json for Client Side

# 1.0.0-rc , 2020-04-15

* Deadline Set Support
* EnableRestCall in appsettings.json

# 1.0.0-rc , 2020-04-14

* ValueType Ok
* Can offer a Simple Rest Wrap for  Grpc

# 1.0.0-rc , 2020-04-07

* Headers of Context is ok
* Contract 1.0.0 is release

# 1.0.0-rc , 2020-04-01

* Plugin System is OK.
* PublishSingleFile --self-contained=false will small Mvc 4Mb/88Mb /  csproj 
* Plugin System with  AssemblyLoadContext 
  * It's recommended that shared dependencies should be loaded into AssemblyLoadContext.Default. This sharing is the common design pattern.
  * AssemblyCatalog ;var files = Directory.EnumerateFiles("DIR", "*.dll", SearchOption.TopDirectoryOnly);
  *   var assembiles = Directory.GetFiles(AppContext.BaseDirectory, "*.dll", SearchOption.TopDirectoryOnly)
            .Select(AssemblyLoadContext.Default.LoadFromAssemblyPath);
  * https://github.com/natemcmaster/DotNetCorePlugins
  * https://codetherapist.com/blog/netcore3-plugin-system/
  * https://medium.com/@mailbox.viksharma/resolve-dependencies-using-mef-and-built-in-ioc-container-of-asp-net-core-aae198cd38b6
  * https://cjansson.se/blog/post/creating-isolated-plugins-dotnetcore
  * DI/Log/Config https://github.com/ibebbs/Cogenity.Extensions
  * https://github.com/thinkabouthub/NugetyCore/wiki/Module-Discovery
  * Learn  Scan From  https://github.com/khellang/Scrutor
  * Learm From .net core 3.0  https://github.com/dapplo/Dapplo.Microsoft.Extensions.Hosting
  * https://github.com/thinkabouthub/NugetyCore
  * https://docs.microsoft.com/en-us/dotnet/core/dependency-loading/understanding-assemblyloadcontext
  * https://docs.microsoft.com/en-us/dotnet/core/tutorials/creating-app-with-plugin-support
  * https://github.com/grpc-ecosystem/grpc-gateway

// TODO


Method : cacheKey , Timeout
Helm Chart

* W3C Tracing  https://gist.github.com/lmolkova/6cd1f61f70dd45c0c61255039695cce8
* API Cache. Throw HashCode
* Simple .NET logging with fully-structured events https://serilog.net
* use message-pack to deir  https://github.com/neuecc/MessagePack-CSharp
* Support stream Call, Like https://github.com/Cysharp/MagicOnion 
* Integrations  yager 
  * https://github.com/Cysharp/MagicOnion#telemetry
  * https://github.com/open-telemetry/opentelemetry-dotnet#auto-collector-implementation-for-activitydiagnosticsource
  * https://github.com/Cysharp/MagicOnion/blob/master/src/MagicOnion.OpenTelemetry/MagicOnionCollector.cs#L306
  * Replace Swagger with 
  * https://github.com/grpc-swagger/grpc-swagger
  * https://github.com/mercari/grpc-http-proxy
* OpenTelemetry exporter, like Prometheus, StackDriver, Zipkin and others.
* GlobalStreamingHubFilters  only Server Side. using StreamingHub


* Hacks such as Domain sharding, resource inlining and image spriting will be counter-productive in an HTTP/2 world.
* HTTP/2 is not a replacement for push technologies such as WebSocket or SSE.
* HTTP/2 Push server can only be processed by browsers, not by applications，Additionally HTTP/2 is not a full duplex protocol so can only respond to requests (though possibly with more than one response thanks to Server Push). You say you only need this for client-server messaging so this may be less of a concern for you. In fact Websockets over HTTP/2 has been approved which will allow the HTTP/2 binary format to be used for websockets by wrapping websockets messages in the HTTP/2 Data frame. 
  
* Combining HTTP/2 and SSE provides efficient HTTP-based bidirectional communication.
* WebSocket will probably remain used but SSE and its EventSource API combined with the power of HTTP/2 will provide the same result in most use cases, just simpler.



* To enumerate all assemblies that the app is composed from, look at Microsoft.Extensions.DependencyModel. E.g. foreach (var l in Microsoft.Extensions.DependencyModel.DependencyContext.Default.RuntimeLibraries) Console.WriteLine(l.Name); will print names of all assemblies that the app is composed from. However, loading all assemblies that the app is composed from tends to scale poorly with size of the application and results in slow startup.



https://github.com/grpc/grpc/blob/master/doc/health-checking.md
 liveness 和 readiness path
  enum ServingStatus {
    UNKNOWN = 0;
    SERVING = 1;
    NOT_SERVING = 2;
  }
service Health {
  rpc Check(string service) returns (ServingStatus status);

  rpc Watch(string service) returns (stream ServingStatus status);
}

