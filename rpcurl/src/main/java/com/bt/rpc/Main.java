package com.bt.rpc;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import picocli.CommandLine;

@QuarkusMain
public class Main implements QuarkusApplication {
    @Override
    public int run(String... args) {
        return new CommandLine(new RpcUrl()).execute(args);
    }


}