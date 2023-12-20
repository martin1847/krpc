
# 设置环境变量

* QUARKUS_DATASOURCE_PASSWORD
* kubectl port-forward service/mysql-primary -n infra 3306:3306

```bash
export HOSTNAME=test-server-76fcf967d7-x4pdg
./build/test-server-1.0.0-runner    -Dquarkus.log.level=DEBUG
```

```bash
export RPC_COOKIE="tk=eyJhbGciOiJFUzI1NiIsImtpZCI6ImJvLXRlc3QtMjExMSJ9.eyJzdWIiOiI1MDNkMmRhNzY5YjI0MGFhODIwNTljOGM4ZGQwNzczOSIsImV4cCI6MTcyNzY2ODEzMn0.DMCQ0alMN02vS8eIUzZthr1lNchPwBU5srVWWCW2QVBq69vjPns-kEOTYt-FzYPNlgboFEZazKigg3T7o1wOrA"
export REMOTE=http://127.0.0.1:50051
rpcurl $REMOTE/test-server/Demo/hello -d '{"name":"123","age":18}'
rpcurl $REMOTE/test-server/Demo/sleep -d '1'
rpcurl $REMOTE/test-server/Demo/testLogicError 
rpcurl $REMOTE/test-server/Demo/testRuntimeException 

```


rpcurl $REMOTE/test-server/Demo/bytesTime
rpcurl $REMOTE/test-server/Demo/incBytes -d '[1,2,3,4]'
rpcurl $REMOTE/test-server/Demo/bytesSum -d '[1,2,3,4]'