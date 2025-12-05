

# 服务端实现

依赖 
* 支持 `springboot` 2/3

类似spring的 `org.mybatis.spring.mapper.MapperScannerConfigurer`

!!! springboot  版本暂时不支持 AOT 编译 !!!

1. 增加依赖
```groovy
// build.gradle
    implementation ("com.zlkj.common:cdn-rpc:1.3.1117") {
        exclude(module: "jakarta.validation-api")
    }
    implementation("tech.krpc:rpc-server-spring:1.0.0.rc1")
```

2. 增加配置

```yaml
rpc:
  server:
    app: ${spring.application.name}
    jwks:  https://zlkj-jwks.oss-cn-shanghai.aliyuncs.com/.well-known/test.bo.jwks.json
```

3. 实现RpcService

```java
@Named
public class DemoServiceImpl implements DemoService {
    @Override
    public RpcResult<String> str(String in) {
        return RpcResult.ok("springboot:got:" + in);
    }
}
```

其余用法跟`quarkus`一样。

