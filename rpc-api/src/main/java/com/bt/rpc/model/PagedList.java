package com.bt.rpc.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
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
}