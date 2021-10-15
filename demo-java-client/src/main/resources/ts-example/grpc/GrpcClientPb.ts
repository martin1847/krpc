/**
 * @fileoverview gRPC-Web generated client stub for com.bt.rpc.internal
 * @enhanceable
 * @public
 */

// GENERATED CODE -- DO NOT EDIT!


/* eslint-disable */
// @ts-nocheck

// https://github.com/improbable-eng/grpc-web/tree/master/client/grpc-web

import * as grpcWeb from 'grpc-web';

import * as internal_pb from './internal_pb';

export class RpcResult {
	code: number; // grpcWeb.StatusCode.OK
	message: string;
	data: any;
//     constructor(code: number, message: string, data: any) {
//         this.code = code;
//         this.message = message;
//         this.data = data;
//     }
}

export class GrpcClient {
  client_: grpcWeb.AbstractClientBase;
  hostname_: string;
  credentials_: null | { [index: string]: string; };
  options_: null | { [index: string]: any; };

  constructor (hostname: string,serviceFullName: string,
               credentials?: null | { [index: string]: string; },
               options?: null | { [index: string]: any; }) {
    if (!options) options = {};
    if (!credentials) credentials = {};
    options['format'] = 'binary';

    this.client_ = new grpcWeb.GrpcWebClientBase(options);
    this.hostname_ = hostname+(hostname.endsWith('/')?'':'/')+serviceFullName+'/';
    this.credentials_ = credentials;
    this.options_ = options;
  }

  methodInfocall = new grpcWeb.MethodDescriptor(
    '',// '/this-is-a-just-placeholder/method-name-here',
    grpcWeb.MethodType.UNARY,
    internal_pb.InputMessage,
    internal_pb.OutputMessage,
//     (request: internal_pb.InputMessage) => {
//       return request.serializeBinary();
//     },
    internal_pb.InputMessage.serializeBinary ,
    internal_pb.OutputMessage.deserializeBinary
  );

  call(method: string,
    request: any,
    metadata: grpcWeb.Metadata | null): Promise<RpcResult>;

  call(method: string,
    request: any,
    metadata: grpcWeb.Metadata | null,
    callback: (err: grpcWeb.Error,
               response: RpcResult) => void): grpcWeb.ClientReadableStream<internal_pb.OutputMessage>;

  call(method: string,
    request: any,
    metadata: grpcWeb.Metadata | null,
    callback?: (err: grpcWeb.Error,
               response: RpcResult) => void) {
    const input = new internal_pb.InputMessage();
    if (request !== undefined && request !== null  ) {
        input.setSe(internal_pb.SerEnum.JSON);
        input.setB(JSON.stringify(request));
    }
    if (callback !== undefined) {
    //  static getHostname(method, methodDescriptor) {
//           // method = hostname + methodDescriptor.name(relative path of this method)
//           return method.substr(0, method.length - methodDescriptor.name.length);
    //
    console.log("client 86 : " + this.client)
      return this.client_.rpcCall(
        this.hostname_ + method,
        input,
        metadata || {},
        this.methodInfocall,
        (err: grpcWeb.Error,  response: internal_pb.OutputMessage) =>{
            if(response){
               callback(err,GrpcClient.toResult(response));
            }else{
               callback(err,response);
            }
          });
    }
    return this.client_.unaryCall(
    this.hostname_ + method,
    input,
    metadata || {},
    this.methodInfocall).then(GrpcClient.toResult);
  }


// Object.assign(new Client(), clientData)
// https://typescript.bootcss.com/generics.html
  protected static toResult(response: internal_pb.OutputMessage) {
    if(response){
       const result = new RpcResult();
       result.code = response.getC();
       result.message = response.getM();
       console.log("response : " + response);
       if(internal_pb.SerEnum.JSON == response.getSe()){
            result.data = JSON.parse(response.getB());
       }else if(null !== response.getS()){
            result.data =  response.getS();
       }else{
            result.data =   response.getB_asU8();
       }
       return result;
    }
    return null;
  }

//   .then(value => {
//       console.log("this gets called after the end of the main stack. the value received and returned is: " + value);
//       return value;
//   });

}

