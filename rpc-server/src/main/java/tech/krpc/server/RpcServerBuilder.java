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
	private final Server server;

	// ADR-0004 (AGENT-001 P0): web-only (@UnsafeWeb) dispatch surface, exposed for the
	// HTTP discover/invoke endpoints. Hidden services are never added here.
	private final Map<String, WebInvoker> webMethods = new HashMap<>();
	private ApiMeta                       webApiMeta;
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
		this.server = init(builder.services,builder.executor);
	}
	
	private  Server init(Map<Object,List<ServerFilter>> services,Executor executor) throws Exception {
		// IOURING-001 Phase 2: flag-gated transport. Default OFF returns the identical
		// ServerBuilder.forPort(port); ON (KRPC_IOURING) wires NettyServerBuilder + io_uring.
		ServerBuilder<?> serverBuilder = IoUringTransport.newServerBuilder(port);
		//	XdsServerBuilder.forPort(port, InsecureServerCredentials.create());
		//System.out.println("========XDS======XDS======XDS=====");

		serverBuilder.executor(executor);

		Builder.PROTO_SERVICE_LIST.forEach(it->{
			serverBuilder.addService(it);
			log.info("[ Origin RpcService Expose ] : {}" , it.bindService().getServiceDescriptor());
		});


		var metaService = new RpcMetaServiceImpl();
		var publicMetaService = new MServiceImpl();
		var metaMethods = new ArrayList<RpcMetaMethod>();
		var webMetaMethods = new ArrayList<RpcMetaMethod>();
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
				for(MethodStub stub : RefUtils.toRpcMethods(ServerContext.applicationName,clz)){
					UnaryMethod methodInvokation = new UnaryMethod(clz ,serviceToInvoke, stub, filterChain);
					//serviceDefBuilder.addMethod(stub.methodDescriptor, ServerCalls.asyncUnaryCall(methodInvokation));
					serviceDefBuilder.addMethod(stub.methodDescriptor, new UnaryCallHandler(methodInvokation));
					if(needMeta) {
						metaMethods.add(toMeta(stub,attr));
					}
					if(needMeta && web){
						webMethods.put(webKey(stub.methodDescriptor.getFullMethodName()), methodInvokation);
						webMetaMethods.add(toMeta(stub,attr));
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
