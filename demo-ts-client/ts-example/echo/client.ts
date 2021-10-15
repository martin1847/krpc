import * as grpcWeb from 'grpc-web';
import * as $ from 'jquery';

// Uncomment either one of the following:
// Option 1: import_style=commonjs+dts
//import {EchoServiceClient} from './echo_grpc_web_pb';

// Option 2: import_style=typescript
import {EchoServiceClient} from './EchoServiceClientPb';

import {EchoRequest, EchoResponse, ServerStreamingEchoRequest, ServerStreamingEchoResponse} from './echo_pb';

class EchoApp {
  static readonly INTERVAL = 500;  // ms
  static readonly MAX_STREAM_MESSAGES = 50;


  constructor(public echoService: EchoServiceClient) {}

  static addMessage(message: string, cssClass: string) {
    $('#first').after($('<div/>').addClass('row').append($('<h2/>').append(
        $('<span/>').addClass('label ' + cssClass).text(message))));
  }

  static addLeftMessage(message: string) {
    this.addMessage(message, 'label-primary pull-left');
  }

  static addRightMessage(message: string) {
    this.addMessage(message, 'label-default pull-right');
  }

  echo(msg: string) {
    EchoApp.addLeftMessage(msg);
    const request = new EchoRequest();
    request.setMessage(msg);
    const call = this.echoService.echo(
        request, {'custom-header-1': 'value1'},
        (err: grpcWeb.RpcError, response: EchoResponse) => {
          if (err) {
            if (err.code !== grpcWeb.StatusCode.OK) {
              EchoApp.addRightMessage(
                  'Error code: ' + err.code + ' "' + err.message + '"');
            }
          } else {
            setTimeout(() => {
              EchoApp.addRightMessage(response.getMessage());
            }, EchoApp.INTERVAL);
          }
        });
    call.on('status', (status: grpcWeb.Status) => {
      if (status.metadata) {
        console.log('Received metadata');
        console.log(status.metadata);
      }
    });
  }



  send(e: {}) {
    const _msg: string = $('#msg').val() as string;
    const msg = _msg.trim();
    $('#msg').val('');  // clear the text box
    if (!msg) return false;

      this.echo(msg);
  }

  load() {
    const self = this;
    $(document).ready(() => {
      // event handlers
      $('#send').click(self.send.bind(self));
      $('#msg').keyup((e) => {
        if (e.keyCode === 13) self.send(e);  // enter key
        return false;
      });

      $('#msg').focus();
    });
  }
}

const echoService = new EchoServiceClient('http://localhost:8080', null, null);

const echoApp = new EchoApp(echoService);
echoApp.load();