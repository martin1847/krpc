package tech.krpc.server;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.common.MService;
import tech.krpc.common.MethodStub;
import tech.krpc.common.RpcConstants;
import tech.krpc.common.RpcMetaService;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.filter.FilterInvokeHelper;
import tech.krpc.util.RefUtils;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.ServerServiceDefinition;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class RpcServerBuilder {
	private final int port;
	// D2 (2026-07-03): concurrent-call cap per HTTP/2 connection; 0 = unlimited.
	private final int maxConcurrentCallsPerConnection;
	private final Server server;

	// ADR-0004 (AGENT-001 P0): web-only (@UnsafeWeb) dispatch surface, exposed for the
	// HTTP discover/invoke endpoints. Hidden services are never added here.
	private final Map<String, WebInvoker> webMethods = new HashMap<>();
	private ApiMeta                       webApiMeta;
	// ADR-0004 (AGENT-001 P1): MCP tool subset — only @UnsafeWeb(agentTool=true) methods.
	private final Map<String, WebInvoker> mcpMethods = new HashMap<>();
	private ApiMeta                       mcpApiMeta;
//	private final static Marshaller<Object> RESPONSE_MARSHALLER = new ResponseMarshaller();
//	private final static Marshaller<InputMessage> REQUEST_MARSHALLER =
//			ProtoLiteUtils.marshaller(InputMessage.getDefaultInstance());
//	// private final static Marshaller<Result> RESPONSE_MARSHALLER = new ResponseMarshaller();
//	private final static Marshaller<OutputMessage> RESPONSE_MARSHALLER =
//			ProtoLiteUtils.marshaller(OutputMessage.getDefaultInstance());


	public static class Builder {
		private final int port;
		private final Map<Object,List<ServerFilter>> services = new HashMap<>();
		public static final List<BindableService> PROTO_SERVICE_LIST = new ArrayList<>();
		Executor executor;
		// D2 (2026-07-03): default 2000; 0 = unlimited (pre-1.0.4 behaviour).
		private int maxConcurrentCallsPerConnection =
				RpcConstants.DEFAULT_MAX_CONCURRENT_CALLS_PER_CONNECTION;

		//public final String applicationName;


		public Builder(String applicationName) {
			this(applicationName, RpcConstants.DEFAULT_PORT);
		}
		public Builder(String applicationName,int port) {
			if(null == applicationName || applicationName.isBlank()){
				throw new RuntimeException("ApplicationName must not be null ! ");
			}
			//this.applicationName = applicationName;
			ServerContext.applicationName = applicationName;
			this.port = port;
		}
		
		public Builder addService(Object service) {
			return  addService(service,Collections.emptyList());
		}

		public Builder addService(Object service,List<ServerFilter> filters) {
			services.put(service,filters);
			return this;
		}

		public Builder executor(Executor executor) {
			this.executor = executor;
			return this;
		}

		/// D2: cap concurrent calls per HTTP/2 connection. 0 = unlimited (the only
		/// unlimited value); a negative value is a configuration error and fails fast.
		public Builder maxConcurrentCallsPerConnection(int max) {
			if (max < 0) {
				throw new IllegalArgumentException(
						"rpc.server.maxConcurrentCallsPerConnection must be >= 0 (0 = unlimited), got " + max);
			}
			this.maxConcurrentCallsPerConnection = max;
			return this;
		}

		public Builder regGlobalFilter(ServerFilter... filters) {
			for (var filter : filters) {
				ServerContext.regGlobalFilter(filter);
			}
			return this;
		}
		//
		//public Builder setDiContext(DiContext diContext){
		//	DiContextFactory.setDiContext(diContext);
		//	return this;
		//}
		//
		public RpcServerBuilder build() throws Exception {
			return new RpcServerBuilder(this);
		}
		
	}
	
	private RpcServerBuilder(Builder builder) throws Exception {
		this.port = builder.port;
		this.maxConcurrentCallsPerConnection = builder.maxConcurrentCallsPerConnection;
		this.server = init(builder.services,builder.executor);
	}
	
	private  Server init(Map<Object,List<ServerFilter>> services,Executor executor) throws Exception {
		ServerBuilder<?> serverBuilder = ServerBuilder.forPort(port);
		//	XdsServerBuilder.forPort(port, InsecureServerCredentials.create());
		//System.out.println("========XDS======XDS======XDS=====");

		serverBuilder.executor(executor);

		// D2 (2026-07-03): app-layer defence-in-depth vs CVE-2026-47244 (HTTP/2
		// stream-flood DoS). maxConcurrentCallsPerConnection lives only on
		// NettyServerBuilder, not the abstract ServerBuilder; forPort() returns the
		// Netty provider at runtime (grpc-netty runtimeOnly). instanceof keeps this
		// compile-safe (grpc-netty compileOnly) and avoids reflection for native.
		if (maxConcurrentCallsPerConnection > 0) {
			if (serverBuilder instanceof NettyServerBuilder) {
				((NettyServerBuilder) serverBuilder)
						.maxConcurrentCallsPerConnection(maxConcurrentCallsPerConnection);
			} else {
				log.warn("maxConcurrentCallsPerConnection={} ignored: server provider {} is not Netty",
						maxConcurrentCallsPerConnection, serverBuilder.getClass().getName());
			}
		}

		Builder.PROTO_SERVICE_LIST.forEach(it->{
			serverBuilder.addService(it);
			log.info("[ Origin RpcService Expose ] : {}" , it.bindService().getServiceDescriptor());
		});


		var metaService = new RpcMetaServiceImpl();
		var publicMetaService = new MServiceImpl();
		var metaMethods = new ArrayList<RpcMetaMethod>();
		var webMetaMethods = new ArrayList<RpcMetaMethod>();
		var mcpMetaMethods = new ArrayList<RpcMetaMethod>();
		services.put(metaService,Collections.emptyList());
		services.put(publicMetaService,Collections.emptyList());


		var typeSets = new HashSet<Class>();
		for(var kv : services.entrySet()) {
			var serviceToInvoke = kv.getKey();
			List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(serviceToInvoke.getClass(), RpcService.class);


			var filterChain =  new FilterInvokeHelper<>
					(ServerContext.GLOBAL_FILTERS,kv.getValue()).buildFilterChain();


			for(Class clz : effectiveClassAnnotations){

				if(!typeSets.add(clz)){//"repeat : "+clz);
					continue;
				}
				io.grpc.ServerServiceDefinition.Builder serviceDefBuilder = ServerServiceDefinition
						.builder(RefUtils.rpcServiceName(ServerContext.applicationName,clz));

				var attr = (RpcService)clz.getAnnotation(RpcService.class);

				boolean needMeta = clz != RpcMetaService.class && clz != MService.class;
				// ADR-0004: web exposure == @UnsafeWeb. Hidden services keep the '-' prefix
				// in their service name and are never registered into the web surface.
				boolean web = clz.isAnnotationPresent(UnsafeWeb.class);
				// ADR-0004 (AGENT-001 P1): agentTool is a strict subset of web — MCP tools only.
				boolean agentTool = web && ((UnsafeWeb) clz.getAnnotation(UnsafeWeb.class)).agentTool();
				for(MethodStub stub : RefUtils.toRpcMethods(ServerContext.applicationName,clz)){
					UnaryMethod methodInvokation = new UnaryMethod(clz ,serviceToInvoke, stub, filterChain);
					//serviceDefBuilder.addMethod(stub.methodDescriptor, ServerCalls.asyncUnaryCall(methodInvokation));
					serviceDefBuilder.addMethod(stub.methodDescriptor, new UnaryCallHandler(methodInvokation));
					if(needMeta) {
						metaMethods.add(toMeta(stub,attr));
					}
					if(needMeta && web){
						var webKey = webKey(stub.methodDescriptor.getFullMethodName());
						webMethods.put(webKey, methodInvokation);
						webMetaMethods.add(toMeta(stub,attr));
						if(agentTool){
							mcpMethods.put(webKey, methodInvokation);
							mcpMetaMethods.add(toMeta(stub,attr));
						}
					}
				}
				var srv = serviceDefBuilder.build();
				serverBuilder.addService(srv);
				var sd = srv.getServiceDescriptor();
				if(needMeta){
					log.info("[ RpcService Expose ] : {}",  sd.getName());
					var index = new AtomicInteger();
						sd.getMethods().forEach(it->
								log.info("     {}). {}",index.incrementAndGet(), it.getFullMethodName() )
								);
				}

			}


		}
		metaService.init(buildApiMeta(metaMethods));
		webApiMeta = buildApiMeta(webMetaMethods);
		mcpApiMeta = buildApiMeta(mcpMetaMethods);
		return serverBuilder.build();
	}

	// strip the leading "app/" so the registry key is the app-relative "Service/method".
	static String webKey(String fullMethodName){
		int slash = fullMethodName.indexOf('/');
		return slash < 0 ? fullMethodName : fullMethodName.substring(slash + 1);
	}

	/// ADR-0004: app-relative "Service/method" -> web dispatcher (web services only).
	public Map<String, WebInvoker> webMethods(){
		return webMethods;
	}

	/// ADR-0004: ApiMeta containing only @UnsafeWeb services and their DTO closure.
	public ApiMeta webApiMeta(){
		return webApiMeta;
	}

	/// ADR-0004 (AGENT-001 P1): "Service/method" -> dispatcher for @UnsafeWeb(agentTool=true) only.
	public Map<String, WebInvoker> mcpMethods(){
		return mcpMethods;
	}

	/// ADR-0004 (AGENT-001 P1): ApiMeta containing only agentTool services (MCP tools/list source).
	public ApiMeta mcpApiMeta(){
		return mcpApiMeta;
	}

	public static ApiMeta buildApiMeta(List<RpcMetaMethod> methods){
		return 	RpcMetaServiceImpl.buildApiMeta(methods);
	}

	public static  RpcMetaMethod toMeta(MethodStub stub,RpcService attr){
		var methodArgs = stub.method.getGenericParameterTypes();
		return new RpcMetaMethod
				(
						stub.methodDescriptor.getServiceName(),
						stub.method.getName(),
						methodArgs.length == 1 ? methodArgs[0]  : null,
						stub.returnType
						,attr.description()
						, stub.method.getDeclaredAnnotations()
				);
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class RpcMetaMethod {

		String servieName;
		String name;
		Type   arg;
		Type   res;

		String description;

		Annotation[] annotations;
	}

	public Server startServer() throws IOException {
		return server.start();
	}
	
}
