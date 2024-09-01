

## 去除`protobuf`

同样的代码，做构建：


```bash
# with protobuf-java
[8/8] Creating image...       [***]                                                                      (8.6s @ 3.84GB)
  96.34MB (69.19%) for code area:    40,084 compilation units
  40.68MB (29.22%) for image heap:  382,688 objects and 54 resources
   2.21MB ( 1.59%) for other data
 139.22MB in total
------------------------------------------------------------------------------------------------------------------------
Top 10 origins of code area:                                Top 10 object types in image heap:
  33.83MB java.base                                           19.24MB byte[] for code metadata
   8.36MB java.xml                                             4.85MB byte[] for java.lang.String
   7.21MB svm.jar (Native Image)                               3.64MB heap alignment
   5.56MB com.google.protobuf.protobuf-java-3.25.1.jar         2.62MB java.lang.String
   5.41MB c.f.jackson.core.jackson-databind-2.16.0.jar         2.48MB java.lang.Class
   3.69MB java.net.http                                      900.34kB byte[] for reflection metadata
   2.37MB org.bouncycastle.bcprov-jdk15on-1.70.jar           871.96kB byte[] for general heap data
   2.24MB io.netty.netty-buffer-4.1.100.Final.jar            646.45kB com.oracle.svm.core.hub.DynamicHubCompanion
   1.92MB common-cdn-1.0.0-runner.jar                        446.47kB java.util.concurrent.ConcurrentHashMap$Node
   1.72MB io.netty.netty-transport-4.1.100.Final.jar         397.84kB c.o.svm.core.hub.DynamicHub$ReflectionMetadata
  23.73MB for 85 more packages                                 4.66MB for 3269 more object types
                              Use '-H:+BuildReport' to create a report with more details.
```

```bash
[8/8] Creating image...       [***]                                                                      (8.8s @ 3.23GB)
  90.23MB (69.40%) for code area:    38,748 compilation units
  37.57MB (28.90%) for image heap:  373,668 objects and 54 resources
   2.21MB ( 1.70%) for other data
 130.02MB in total
------------------------------------------------------------------------------------------------------------------------
Top 10 origins of code area:                                Top 10 object types in image heap:
  33.76MB java.base                                           18.01MB byte[] for code metadata
   8.36MB java.xml                                             4.71MB byte[] for java.lang.String
   7.18MB svm.jar (Native Image)                               2.56MB java.lang.String
   5.42MB c.f.jackson.core.jackson-databind-2.16.0.jar         2.40MB java.lang.Class
   3.68MB java.net.http                                        2.18MB heap alignment
   2.37MB org.bouncycastle.bcprov-jdk15on-1.70.jar           874.71kB byte[] for reflection metadata
   2.24MB io.netty.netty-buffer-4.1.100.Final.jar            866.48kB byte[] for general heap data
   1.86MB common-cdn-1.0.0-runner.jar                        632.20kB com.oracle.svm.core.hub.DynamicHubCompanion
   1.72MB io.netty.netty-transport-4.1.100.Final.jar         446.25kB java.util.concurrent.ConcurrentHashMap$Node
   1.42MB org.apache.httpcomponents.httpclient-4.5.14.jar    395.70kB byte[] for embedded resources
  21.96MB for 83 more packages                                 4.57MB for 3139 more object types
                              Use '-H:+BuildReport' to create a report with more details.
```

大小减少了9MB （～7%）