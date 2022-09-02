package com.bt.rpc.common.meta;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 */

@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class Dto {

    /// classSimpleName
    String name;

    int typeVar;

    // 是否做为入参
    boolean input;

    List<Property> fields;

    // 是否只是做为范型占位符
    boolean parameterized;

    String doc;
    //
    //public boolean hasChild() {
    //    return null != fields && !fields.isEmpty();
    //}

    public Dto(String name, int typeVar, boolean input,String doc) {
        this(name,typeVar,input,doc,false);
    }

    public Dto(String name, int typeVar, boolean input,String doc,boolean parameterized) {
        this.name = name;
        this.typeVar = typeVar;
        this.input = input;
        this.doc = doc;
        this.parameterized = parameterized;
    }
}