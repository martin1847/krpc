
# grpc-web use the docker compile
docker run --rm -it --mount type=bind,source="$(pwd)",dst=/tmp/proto \
 -w /tmp/proto grpcweb/prereqs:1.3.0 bash


# dart-docker
# https://github.com/robojones/protoc-dart
docker run --rm -it --mount type=bind,source="$(pwd)",dst=/tmp/proto \
 -w /tmp/proto robojones/protoc-dart:latest bash

mkdir -p generated && protoc --dart_out=grpc:generated internal.proto

# java mac protoc or docker
# Linux: apt-get install protobuf-compiler
# Mac homebrew: brew install protobuf
# protoc  --java_out=outj internal.proto