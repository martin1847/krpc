package com.bt.rpc.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PagedList<DTO> implements Serializable {

        /// int is enough.maybeNull when use lastkey
        Integer totalCount ;

        /// current page data 
        List<DTO> data;

        /// use last key to paging
        String lastKey;

    }