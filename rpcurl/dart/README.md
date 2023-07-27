A simple command-line application.


https://hub.docker.com/_/microsoft-windows-servercore

The default entrypoint is for this image is Cmd.exe. To run the image:

docker run mcr.microsoft.com/windows/servercore:ltsc2022

$ dart pub get
更新依赖
```bash
dart run
dart compile exe  bin/rpcurl.dart  -o rpcurl
```

## alpline build

dart:2.17 bash
```bash
docker run --rm -it --mount type=bind,source="$(pwd)",dst=/wk dart:2.17 bash
dart
```

github : 增加私钥为Repository secrets.`SSH_PRIVATE_KEY`
https://github.com/martin2038/bt-rpc/settings/secrets/actions
KNOWN_HOSTS: After launch ssh-keyscan github.com command, 
it's important to copy the line that belongs to github.com ssh-rsa [KEY]

配合workflow
```bash

```