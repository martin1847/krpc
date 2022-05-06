
# 直接宿主机安装
brew install go



## docker镜像
https://github.com/mfycheng/protoc-gen-go/blob/master/Dockerfile
docker pull mfycheng/protoc-gen-go:0.5

docker run -v --mount type=bind,source="$(pwd)"/go1,dst=/proto --mount type=bind,source="$(pwd)"/go2,dst=/genproto  mfycheng/protoc-gen-go:0.5

-v go1:/proto -v go2:/genproto

docker run  --rm -it --entrypoint /bin/bash --mount type=bind,source="$(pwd)"/go1,dst=/proto --mount type=bind,source="$(pwd)"/go2,dst=/genproto mfycheng/protoc-gen-go:0.5
