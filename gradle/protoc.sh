docker run --rm -it --mount type=bind,source="$(pwd)",dst=/tmp/proto \
 -w /tmp/proto grpcweb/prereqs:1.3.0 bash

 # protoc  --java_out=outj internal.proto