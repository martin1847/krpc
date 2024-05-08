

# 客户端实现

依赖 
* 支持 `springboot` 2/3

类似spring的 `org.mybatis.spring.mapper.MapperScannerConfigurer`


1. 增加依赖
```groovy
// build.gradle
    implementation ("com.zlkj.common:cdn-rpc:1.3.1117") {
        exclude(module: "jakarta.validation-api")
    }
    implementation("tech.krpc:rpc-client-spring:1.0.0.rc1")
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