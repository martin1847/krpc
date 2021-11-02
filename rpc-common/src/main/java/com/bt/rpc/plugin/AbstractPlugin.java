//package com.bt.rpc.plugin;
//
//import lombok.Data;
//
//@Data
//public abstract class AbstractPlugin implements Plugin{
//
//    private Boolean disable;
//
//    private Integer rank;
//
//    public abstract String getName();
//
//    public Boolean getDisable() {
//        return this.disable != null ? this.disable : Boolean.FALSE;
//    }
//
//    public Integer getRank() {
//        return this.rank != null ? this.rank : 100;
//    }
//
//    public abstract void init();
//
//    public String ExampleConfig() {
//        return null;
//    }
//}
