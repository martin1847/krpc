

# 客户端实现

依赖 
* spring2

类似spring的 `org.mybatis.spring.mapper.MapperScannerConfigurer`


1. 增加依赖
```groovy
// build.gradle
    implementation ("com.zlkj.common:cdn-rpc:1.3.1117") {
        exclude(module: "jakarta.validation-api")
    }
    implementation("com.bt.rpc:rpc-client-spring2:1.0.0")
```

2. 增加配置

```yaml
rpc:
  enable: true
  clients:
    common-cdn:
      url: http://common-cdn.common:50051
      scan: com.zlkj.common.cdn.service
```