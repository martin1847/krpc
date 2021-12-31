package com.bt.rpc.common.meta;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

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

    public boolean hasChild() {
        return null != fields && !fields.isEmpty();
    }

    public Dto(String name, int typeVar, boolean input) {
        this.name = name;
        this.typeVar = typeVar;
        this.input = input;
    }

    public Dto(String name, int typeVar, boolean input,boolean parameterized) {
        this.name = name;
        this.typeVar = typeVar;
        this.input = input;
        this.parameterized = parameterized;
    }

    //public Dto(String name, List<String> typeVarList, boolean input) {
    //    this.name = name;
    //    this.typeVarList = typeVarList;
    //    this.input = input;
    //    if(null!=typeVarList){
    //        typeVar = typeVarList.size();
    //    }
    //}

    @JsonIgnore
    public String getTypeName() {
        //if (null != typeVarList && !typeVarList.isEmpty()) {
        //    var types = typeVarList.stream().collect(Collectors.joining(",", "<", ">"));
        //    return name + types;
        //}

        if (0 == typeVar) {
            return name;
        }

        if(hasChild() && 1 == typeVar){
            //try get Gentype from first Child
            var gName ="T0";
            for(var f : fields){
                var rowType = f.getType().rawType;
                if(rowType.parameterized){
                    gName = rowType.name;
                    break;
                }
                if(f.getType().isGenerics()){
                    gName= f.getType().generics.get(0).getRawType().getTypeName();
                    break;
                }
            }
            return  name +'<'+gName+'>';
        }

        var sb = new StringBuilder();
        sb.append(name).append('<');
        for (int i = 0; i < typeVar; i++) {
            sb.append('T').append(i).append(',');
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append('>');
        return sb.toString();
    }
}