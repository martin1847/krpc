import * as grpcWeb from 'grpc-web';

import * as internal_pb from './internal_pb';


export class SkeletonClient {
  constructor (hostname: string,
               credentials?: null | { [index: string]: string; },
               options?: null | { [index: string]: any; });

  call(
    request: internal_pb.InputMessage,
    metadata: grpcWeb.Metadata | undefined,
    callback: (err: grpcWeb.RpcError,
               response: internal_pb.OutputMessage) => void
  ): grpcWeb.ClientReadableStream<internal_pb.OutputMessage>;

}

export class SkeletonPromiseClient {
  constructor (hostname: string,
               credentials?: null | { [index: string]: string; },
               options?: null | { [index: string]: any; });

  call(
    request: internal_pb.InputMessage,
    metadata?: grpcWeb.Metadata
  ): Promise<internal_pb.OutputMessage>;

}

