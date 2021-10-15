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


export class SkeletonClient {
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

  methodInfocall = new grpcWeb.AbstractClientBase.MethodInfo(
    internal_pb.OutputMessage,
    (request: internal_pb.InputMessage) => {
      return request.serializeBinary();
    },
    internal_pb.OutputMessage.deserializeBinary
  );

  call(
    request: internal_pb.InputMessage,
    metadata: grpcWeb.Metadata | null): Promise<internal_pb.OutputMessage>;

  call(
    request: internal_pb.InputMessage,
    metadata: grpcWeb.Metadata | null,
    callback: (err: grpcWeb.Error,
               response: internal_pb.OutputMessage) => void): grpcWeb.ClientReadableStream<internal_pb.OutputMessage>;

  call(
    request: internal_pb.InputMessage,
    metadata: grpcWeb.Metadata | null,
    callback?: (err: grpcWeb.Error,
               response: internal_pb.OutputMessage) => void) {
    if (callback !== undefined) {
      return this.client_.rpcCall(
        this.hostname_ +
          '/com.bt.rpc.internal.Skeleton/call',
        request,
        metadata || {},
        this.methodInfocall,
        callback);
    }
    return this.client_.unaryCall(
    this.hostname_ +
      '/com.bt.rpc.internal.Skeleton/call',
    request,
    metadata || {},
    this.methodInfocall);
  }

}

