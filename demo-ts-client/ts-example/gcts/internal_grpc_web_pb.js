/**
 * @fileoverview gRPC-Web generated client stub for com.bt.rpc.internal
 * @enhanceable
 * @public
 */

// GENERATED CODE -- DO NOT EDIT!


/* eslint-disable */
// @ts-nocheck



const grpc = {};
grpc.web = require('grpc-web');

const proto = {};
proto.com = {};
proto.com.bt = {};
proto.com.bt.rpc = {};
proto.com.bt.rpc.internal = require('./internal_pb.js');

/**
 * @param {string} hostname
 * @param {?Object} credentials
 * @param {?grpc.web.ClientOptions} options
 * @constructor
 * @struct
 * @final
 */
proto.com.bt.rpc.internal.SkeletonClient =
    function(hostname, credentials, options) {
  if (!options) options = {};
  options.format = 'binary';

  /**
   * @private @const {!grpc.web.GrpcWebClientBase} The client
   */
  this.client_ = new grpc.web.GrpcWebClientBase(options);

  /**
   * @private @const {string} The hostname
   */
  this.hostname_ = hostname;

};


/**
 * @param {string} hostname
 * @param {?Object} credentials
 * @param {?grpc.web.ClientOptions} options
 * @constructor
 * @struct
 * @final
 */
proto.com.bt.rpc.internal.SkeletonPromiseClient =
    function(hostname, credentials, options) {
  if (!options) options = {};
  options.format = 'binary';

  /**
   * @private @const {!grpc.web.GrpcWebClientBase} The client
   */
  this.client_ = new grpc.web.GrpcWebClientBase(options);

  /**
   * @private @const {string} The hostname
   */
  this.hostname_ = hostname;

};


/**
 * @const
 * @type {!grpc.web.MethodDescriptor<
 *   !proto.com.bt.rpc.internal.InputMessage,
 *   !proto.com.bt.rpc.internal.OutputMessage>}
 */
const methodDescriptor_Skeleton_call = new grpc.web.MethodDescriptor(
  '/com.bt.rpc.internal.Skeleton/call',
  grpc.web.MethodType.UNARY,
  proto.com.bt.rpc.internal.InputMessage,
  proto.com.bt.rpc.internal.OutputMessage,
  /**
   * @param {!proto.com.bt.rpc.internal.InputMessage} request
   * @return {!Uint8Array}
   */
  function(request) {
    return request.serializeBinary();
  },
  proto.com.bt.rpc.internal.OutputMessage.deserializeBinary
);


/**
 * @param {!proto.com.bt.rpc.internal.InputMessage} request The
 *     request proto
 * @param {?Object<string, string>} metadata User defined
 *     call metadata
 * @param {function(?grpc.web.RpcError, ?proto.com.bt.rpc.internal.OutputMessage)}
 *     callback The callback function(error, response)
 * @return {!grpc.web.ClientReadableStream<!proto.com.bt.rpc.internal.OutputMessage>|undefined}
 *     The XHR Node Readable Stream
 */
proto.com.bt.rpc.internal.SkeletonClient.prototype.call =
    function(request, metadata, callback) {
  return this.client_.rpcCall(this.hostname_ +
      '/com.bt.rpc.internal.Skeleton/call',
      request,
      metadata || {},
      methodDescriptor_Skeleton_call,
      callback);
};


/**
 * @param {!proto.com.bt.rpc.internal.InputMessage} request The
 *     request proto
 * @param {?Object<string, string>=} metadata User defined
 *     call metadata
 * @return {!Promise<!proto.com.bt.rpc.internal.OutputMessage>}
 *     Promise that resolves to the response
 */
proto.com.bt.rpc.internal.SkeletonPromiseClient.prototype.call =
    function(request, metadata) {
  return this.client_.unaryCall(this.hostname_ +
      '/com.bt.rpc.internal.Skeleton/call',
      request,
      metadata || {},
      methodDescriptor_Skeleton_call);
};


module.exports = proto.com.bt.rpc.internal;

