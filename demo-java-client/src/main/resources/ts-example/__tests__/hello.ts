/**
 *
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import * as grpcWeb from 'grpc-web';
// import * as $ from 'jquery';

// Uncomment either one of the following:
// Option 1: import_style=commonjs+dts
// import {EchoServiceClient} from './echo_grpc_web_pb';

// Option 2: import_style=typescript
import {RpcResult,GrpcClient} from '../grpc/GrpcClientPb';

// import {EchoRequest, EchoResponse, ServerStreamingEchoRequest, ServerStreamingEchoResponse} from './echo_pb';

class TimeReq {
    name: string;
    age:  number;
    constructor(name: string, age: number) {
        this.name = name;
        this.age = age;
    }
}
const timeService = new GrpcClient('http://localhost:8080', 'com.bt.demo.TimeService');

const request = new TimeReq("hello-node-jest",18);
const call = timeService.call("hello",
    request, {'custom-header-1': 'value1'},
    (err: grpcWeb.RpcError, response: RpcResult) => {
      if (err) {
        if (err.code !== grpcWeb.StatusCode.OK) {
          console.log(
              'Error code: ' + err.code + ' "' + err.message + '"');
        }
      } else {
            console.log(" client.ts line 73 get result :" + JSON.stringify(response));
      }
    });
call.on('status', (status: grpcWeb.Status) => {
  if (status.metadata) {
    console.log('Received metadata');
    console.log(status.metadata);
  }
});

