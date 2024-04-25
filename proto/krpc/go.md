
# 直接宿主机安装
brew install go



## docker镜像
https://hub.docker.com/r/onosproject/protoc-go

docker pull onosproject/protoc-go:v1.2.1

docker run  --rm -it --entrypoint /bin/bash  --mount type=bind,source="$(pwd)",dst=/proto onosproject/protoc-go:v1.2.1  

protoc --go_out=. --go_opt=paths=source_relative \
--go-grpc_out=. --go-grpc_opt=paths=source_relative \
internal.proto


