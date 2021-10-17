docker run --rm -it --mount type=bind,source="$(pwd)"/ts-example,dst=/tmp/ts-example \
 -w /tmp/ts-example grpcweb/prereqs:1.3.0 bash

#  npm install -g typescript
#   npm install ;
#   npm link grpc-web &&  tsc &&    npx webpack