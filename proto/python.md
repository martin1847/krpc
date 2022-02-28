https://grpc.io/docs/languages/python/basics/

 pip3 install grpcio-tools  -i https://pypi.tuna.tsinghua.edu.cn/simple

mkdir -p  ./generated/py
python3 -m grpc_tools.protoc -I. --python_out=./generated/py --grpc_python_out=./generated/py ./internal.proto




# vir env

If you cannot upgrade pip due to a system-owned installation, you can run the example in a virtualenv:

```bash
$ python -m pip install virtualenv
$ virtualenv venv
$ source venv/bin/activate
$ python -m pip install --upgrade pip
```