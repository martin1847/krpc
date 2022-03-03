https://grpc.io/docs/languages/python/basics/

 pip3 install grpcio-tools  -i https://pypi.tuna.tsinghua.edu.cn/simple

mkdir -p  ./generated/py
python3 -m grpc_tools.protoc -I. --python_out=./generated/py --grpc_python_out=./generated/py ./internal.proto




# venv


```bash
export VER=3.8
python3 -m venv ~/.venv/$VER --system-site-packages
source ~/.venv/3.8/bin/activate
# 退出虚拟环境
deactivate
$ python -m pip install --upgrade pip
```