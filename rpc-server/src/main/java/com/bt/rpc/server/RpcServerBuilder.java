package com.bt.rpc.server;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.common.FilterInvokeHelper;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.common.RpcMetaService;
import com.bt.rpc.server.RpcMetaServiceImpl.RpcMetaMethod;
import com.bt.rpc.common.MethodStub;
import com.bt.rpc.util.RefUtils;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class RpcServerBuilder {
	private final int port;
	private final Server server;
//	private final static Marshaller<Object> RESPONSE_MARSHALLER = new ResponseMarshaller();
//	private final static Marshaller<InputMessage> REQUEST_MARSHALLER =
//			ProtoLiteUtils.marshaller(InputMessage.getDefaultInstance());
//	// private final static Marshaller<Result> RESPONSE_MARSHALLER = new ResponseMarshaller();
//	private final static Marshaller<OutputMessage> RESPONSE_MARSHALLER =
//			ProtoLiteUtils.marshaller(OutputMessage.getDefaultInstance());


	public static class Builder {
		private final int port;
		private final Map<Object,List<ServerFilter>> services = new HashMap<>();
//		private final List<BindableService> protoServiceList = new ArrayList<>();

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
//			List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(service.getClass(), GrpcService.class);
//			if( effectiveClassAnnotations.size() != 1 ) {
//				String msg = effectiveClassAnnotations.isEmpty() ? "No Interfaces implementing GrpcService annotation" :
//					"More than one interface implementing GRPC annotation";
//				throw new IllegalArgumentException(msg);
//			}
//			if(service instanceof  BindableService){
//				protoServiceList.add((BindableService) service);
//			}else{
			return  addService(service,Collections.emptyList());
//			}
			//return this;
		}

		public Builder addService(Object service,List<ServerFilter> filters) {
			services.put(service,filters);
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
		this.server = init(Collections.emptyList(),builder.services);
	}
	
	private  Server init(List<BindableService> protoService,Map<Object,List<ServerFilter>> services) throws Exception {
		ServerBuilder<?> serverBuilder = ServerBuilder.forPort(port);

		protoService.forEach(it->{
			serverBuilder.addService(it);
			log.info("[ Origin RpcService Expose: ] {}" , it.bindService().getServiceDescriptor());
		});


		var metaService = new RpcMetaServiceImpl();
		var metaMethods = new ArrayList<RpcMetaMethod>();
		services.put(metaService,Collections.emptyList());

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

				boolean needMeta = clz != RpcMetaService.class;
				for(MethodStub stub : RefUtils.toRpcMethods(ServerContext.applicationName,clz)){

					UnaryMethod methodInvokation = new UnaryMethod(clz ,serviceToInvoke, stub, filterChain);
					//serviceDefBuilder.addMethod(stub.methodDescriptor, ServerCalls.asyncUnaryCall(methodInvokation));

					serviceDefBuilder.addMethod(stub.methodDescriptor, new UnaryCallHandler(methodInvokation));
					if(needMeta) {
						var methodArgs = stub.method.getParameterTypes();

						metaMethods.add(new RpcMetaMethod
										(
							stub.methodDescriptor.getServiceName(),
												stub.method.getName(),
												methodArgs.length == 1 ? methodArgs[0]  : null,
												stub.returnType
												,attr.description()
												, Stream.of(stub.method.getDeclaredAnnotations())
														.map(Annotation::toString).collect(Collectors.toList())
										));
					}
				}
				var srv = serviceDefBuilder.build();
				serverBuilder.addService(srv);
				if(needMeta){
					var sd = srv.getServiceDescriptor();
					log.info("[ RpcService Expose: ] {}",  sd.getName());
					var index = new AtomicInteger();
					sd.getMethods().forEach(it->
							log.info("     {}). {}",index.incrementAndGet(), it.getFullMethodName() )
							);

				}

			}


		}
		metaService.init(metaMethods);
		return serverBuilder.build();
	}
	
	public Server startServer() throws IOException {
		return server.start();
	}
	
}
