package com.btyx.rpc.gen.meta;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 */

@Data
@NoArgsConstructor
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

    public boolean hasChild() {
        return null != fields && !fields.isEmpty();
    }

    public boolean isEnum() {
        return hasChild() && fields.get(0).type == null;
    }

    public String getInnerType() {
        if(hasChild() && 1 == typeVar){
            //try get Gentype from first Child
            var gName ="T0";
            for(var f : fields){
                var rowType = f.getType().rawType;
                if(rowType.parameterized){
                    gName = rowType.name;
                    break;
                }
                if(f.getType().isGeneric()){
                    gName= f.getType().generics.get(0).getRawType().getTypeName();
                    break;
                }
            }
            return gName;
        }
        return "";
    }
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
            return  name +'<'+getInnerType()+'>';
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

    public void setName(String name) {
        this.name = name;
        if(null == originName){
            originName = name;
        }
    }

    public String originName;
}