
# grpc-web use the docker compile
docker run --rm -it --mount type=bind,source="$(pwd)",dst=/tmp/proto \
 -w /tmp/proto grpcweb/prereqs:1.3.0 bash

## for ts + proto FMT
// protoc internal.proto  --js_out=import_style=commonjs,binary:js --grpc-web_out=import_style=typescript,mode=grpcweb:js
// https://github.com/grpc/grpc-web#wire-format-mode

## for nodejs
npm install -g grpc-tools
grpc_tools_node_protoc --js_out=import_style=commonjs,binary:js --grpc_out=grpc_js:js internal.proto


## 纯TS版本，不依赖 protobuf ，https://github.com/timostamm/protobuf-ts
npm install @protobuf-ts/plugin
npx protoc --ts_out ./js --proto_path . ./internal.proto


# dart-docker
# https://github.com/robojones/protoc-dart
docker run --rm -it --mount type=bind,source="$(pwd)",dst=/tmp/proto \
 -w /tmp/proto robojones/protoc-dart:latest bash

mkdir -p generated && protoc --dart_out=grpc:generated internal.proto

# java mac protoc or docker
# Linux: apt-get install protobuf-compiler
# Mac homebrew: brew install protobuf
# protoc  --java_out=outj internal.proto

## 大部分语言的ptoroc


docker run --rm rvolosatovs/protoc --help 
只有proto，没有grpc的，换成`tonic-build`
```bash
docker run --rm -it --mount type=bind,source="$(pwd)",dst=/tmp/proto -w /tmp/proto rvolosatovs/protoc \
--rust_out=experimental-codegen=enabled,kernel=cpp:/tmp/proto  -I/tmp/proto internal.proto
```