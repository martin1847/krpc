/**
 * @fileoverview gRPC-Web generated client stub for com.bt.rpc.internal
 * @enhanceable
 * @public
 */

// GENERATED CODE -- DO NOT EDIT!


/* eslint-disable */
// @ts-nocheck


import * as grpcWeb from 'grpc-web';

import * as internal_pb from './internal_pb';


export class GrpcClient {
  client_: grpcWeb.AbstractClientBase;
  hostname_: string;
  credentials_: null | { [index: string]: string; };
  options_: null | { [index: string]: any; };

  constructor (hostname: string,
               credentials?: null | { [index: string]: string; },
               options?: null | { [index: string]: any; }) {
    if (!options) options = {};
    if (!credentials) credentials = {};
    options['format'] = 'binary';

    this.client_ = new grpcWeb.GrpcWebClientBase(options);
    this.hostname_ = hostname;
    this.credentials_ = credentials;
    this.options_ = options;
  }

  methodInfocall = new grpcWeb.MethodDescriptor(
    '/com.bt.rpc.internal.Grpc/call',
    grpcWeb.MethodType.UNARY,
    internal_pb.InputProto,
    internal_pb.OutputProto,
    (request: internal_pb.InputProto) => {
      return request.serializeBinary();
    },
    internal_pb.OutputProto.deserializeBinary
  );

  call(
    request: internal_pb.InputProto,
    metadata: grpcWeb.Metadata | null): Promise<internal_pb.OutputProto>;

  call(
    request: internal_pb.InputProto,
    metadata: grpcWeb.Metadata | null,
    callback: (err: grpcWeb.RpcError,
               response: internal_pb.OutputProto) => void): grpcWeb.ClientReadableStream<internal_pb.OutputProto>;

  call(
    request: internal_pb.InputProto,
    metadata: grpcWeb.Metadata | null,
    callback?: (err: grpcWeb.RpcError,
               response: internal_pb.OutputProto) => void) {
    if (callback !== undefined) {
      return this.client_.rpcCall(
        this.hostname_ +
          '/com.bt.rpc.internal.Grpc/call',
        request,
        metadata || {},
        this.methodInfocall,
        callback);
    }
    return this.client_.unaryCall(
    this.hostname_ +
      '/com.bt.rpc.internal.Grpc/call',
    request,
    metadata || {},
    this.methodInfocall);
  }

}

