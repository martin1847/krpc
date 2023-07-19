package com.bt.model;


import java.io.Serializable;
import java.util.List;

public class PagedList<DTO> implements Serializable {

    /// int is enough.maybeNull when use lastkey
    Integer count;

    /// current page data
    List<DTO> data;

    /// use last key to paging
    String lastKey;

    public PagedList() {
    }

    public PagedList(Integer count, List<DTO> data) {
        this.count = count;
        this.data = data;
    }



    public PagedList( String lastKey, List<DTO> data) {
        this.lastKey = lastKey;
        this.data = data;
    }
    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }
    public List<DTO> getData() {
        return data;
    }

    public void setData(List<DTO> data) {
        this.data = data;
    }

    public String getLastKey() {
        return lastKey;
    }

    public void setLastKey(String lastKey) {
        this.lastKey = lastKey;
    }
    //
    //@Override
    //public String toString() {
    //    return "PagedList{" +
    //            "count=" + count +
    //            ", data=" + data +
    //            ", lastKey='" + lastKey + '\'' +
    //            '}';
    //}
}