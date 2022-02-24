import 'package:args/args.dart';

import 'package:btrpc/btrpc.dart';
import 'package:grpc/grpc.dart';
import 'dart:convert';
import 'dart:io';
import 'dart:core';

/**
 * 
 * rpcurl https://example.testbtyxapi.com/demo-java-server/Demo/hello 
 * rest test the data
 *  dart compile exe  src/rpcurl.dart    
 * 
Code	Meaning
0	Success
1	Warnings
2	Errors
 */
void main(List<String> arguments) async {
  exitCode = 0; // presume success

  final String META_SERVICE = "RpcMeta";
  final String DEFAULT_METHOD = "listApis";

  final String ENV_URL = "RPC_URL";
  final String ENV_APP = "RPC_APP";
  final String ENV_TOKEN = "RPC_TOKEN";
  final String ENV_CID = "RPC_CID";
  final String ENV_CMETA = "RPC_CMETA";

  final String LOCAL = 'http://127.0.0.1:50051';

  final String VERSION = 'rpcurl-1.0 2022.02.24';

  final parser = ArgParser()
    ..addFlag('no-url',
        negatable: false, abbr: 'L', help: '本机测试，本机测试 url=$LOCAL')
    ..addFlag('no-web', negatable: false, abbr: 'W', help: '测试非 UnsafeWeb 服务')
    ..addFlag('no-pretty', negatable: false, abbr: 'P', help: 'NO pretty json')
    ..addFlag('help', negatable: false, abbr: 'h', help: 'show usage')
    ..addOption('url',
        abbr: 'u',
        help:
            '服务地址,默认参数,必传,也可通过环境变量`$ENV_URL`传递,如: https://example.testbtyxapi.com/demo-java-server/Demo/hello')
    ..addOption('app',
        abbr: 'a', help: '服务项目名,也可通过环境变量`$ENV_APP`传递,如 demo-java-server')
    ..addOption('service', abbr: 's', help: '服务名', defaultsTo: META_SERVICE)
    ..addOption('method', abbr: 'm', help: '方法名', defaultsTo: DEFAULT_METHOD)
    ..addOption('data',
        abbr: 'd', help: '入参json,优先级高于file,如 -d \'{"name":"rpcurl"}\'')
    ..addOption('file', abbr: 'f', help: '入参jsonFile,如 -f test.json')
    // ..addOption('cookie', abbr: 'c', help: 'set cookie: <a=b; c=d>')
    ..addOption('token',
        abbr: 't',
        help: 'authorization: Bearer <accessToken>,也可通过环境变量`$ENV_TOKEN`传递')
    ..addOption('clientId', abbr: 'i', help: '设置c-id,或者环境变量 `$ENV_CID`')
    ..addOption('clientMeta',
        abbr: 'M', help: '设置 c-meta(json),或者环境变量 `$ENV_CMETA`')
    ..addFlag('version', abbr: 'V', help: '打印版本号 $VERSION');

  ArgResults args = parser.parse(arguments);

  if (args['version']) {
    print(VERSION);
    exit(0);
  }
  // final paths = argResults.rest;
  final envVarMap = Platform.environment;

  bool local = args['no-url'];
  String? url;
  if (local) {
    url = LOCAL;
  } else {
    url = args['url'] ??
        (args.rest.isNotEmpty ? args.rest[0] : null) ??
        envVarMap[ENV_URL];
  }

  if (args['help'] || null == url) {
    showUsage(parser);
    exit(1);
  }

  var uri = Uri.parse(url);

  var paths = uri.pathSegments;
  String? app =
      paths.isNotEmpty ? paths.first : args['app'] ?? envVarMap[ENV_APP];
  if (null == app) {
    showUsage(parser);
    exit(1);
  }

  var sName = paths.length >= 2 ? paths[1] : args['service'];
  var mName = paths.length >= 3 ? paths[2] : args['method'];
  if ((args['no-web'] || sName == META_SERVICE) && !app.startsWith('-')) {
    app = "-" + app;
  }

  var options = ChannelOptions(
      credentials: 'http' == uri.scheme
          ? ChannelCredentials.insecure()
          : ChannelCredentials.secure());

  final channel = ClientChannel(uri.host, port: uri.port, options: options);

  final baseService = BaseService(
      channel,
      app,
      sName,
      ServiceConfig(
          accessToken: args['token'] ?? envVarMap[ENV_TOKEN],
          clientId: args['clientId'] ??
              envVarMap[ENV_CID] ??
              'drpcurl-${Platform.localHostname}',
          clientMeta:
              args['clientMeta'] ?? envVarMap[ENV_CMETA] ?? '{"os":4}'));

  String? param = args['data'];
  String? file = args['file'];
  if (null == param && file != null) {
    param = await File(file).readAsString();
  }

  try {
    print('');
    var portStr = uri.port == 443 || uri.port == 80 ? "" : ':${uri.port}';
    print('${uri.scheme}://${uri.host}$portStr/$app/$sName/$mName');
    if (null != param) {
      print('${jsonDecode(param)}');
    }
    final response =
        await baseService.call0(mName, param, toJson: (String p0) => p0);

    print('');
    var jsonRes = {'code': response.code, 'data': response.data};
    var msg = response.message;
    if (null != msg && msg.isNotEmpty) {
      jsonRes['message'] = msg;
    }
    print(args['no-pretty']
        ? jsonEncode(jsonRes)
        : JsonEncoder.withIndent('  ').convert(jsonRes));
  } catch (e) {
    print('');
    print('Caught error: $e');
  }

  // dcat(paths, showLineNumbers: argResults[lineNumber] as bool);

  await channel.shutdown();
}

void showUsage(ArgParser parser) {
  print(
      'Usage: rpcurl https://demo.btyxapi.com/appName/Demo/methodName -f param.json');
  print('测试rpc服务\r\n');
  print(parser.usage);
}
// Future<void> _handleError(String path) async {
//   if (await FileSystemEntity.isDirectory(path)) {
//     stderr.writeln('error: $path is a directory');
//   } else {
//     exitCode = 2;
//   }
// }
