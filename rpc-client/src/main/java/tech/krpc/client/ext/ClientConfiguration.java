package tech.krpc.client.ext;

import java.util.List;

import lombok.Data;

/*
"Rpc": {
    "Client": {

      "Remote": [{
        "Host":"localhost",
        //"Port":50051,
        //"Https":false,
        "ServiceScan":"Com.Bt.Demo"
      }],

      // Optional Below
      "GlobalFilter": "RpcClientWeb.TestLogFilter",
      "ServiceConfig":[{
        "Name":"ITimeService",
        "Filter":"RpcClientWeb.TestLogFilter"
      }],
      "RpcChannelOptions": {
        "MaxSendMessageSize": 128
      }
    }
  }
 */
@Data
//@ConfigMapping(prefix = "rpc.client")
public class ClientConfiguration {
    public String globalFilter;

    public int maxSendMessageSize  =2*1024*1024;// 2MB

    public boolean throwOperationCanceledOnCancellation =true;

    public List<Remote> Remotes;

    //      "ServiceConfig":[{
    //        "Name":"ITimeService",
    //        "Filter":"RpcClientWeb.TestLogFilter"
    //      }],
    //public List<ClientServiceConfig> serviceConfigs;

}