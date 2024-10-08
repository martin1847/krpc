/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.gen.meta;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 7:30 PM
 */
@Data
@NoArgsConstructor
public class PropertyType {

    Dto rawType;

    // this shuld also  be Property
    List<PropertyType> generics;

    public PropertyType(Dto rawType) {
        this.rawType = rawType;
    }

    public PropertyType(Dto rawType, List<PropertyType> generics) {
        this.rawType = rawType;
        this.generics = generics;
    }

    public boolean isGeneric() {
        return null != generics && generics.size()>0;
    }

    public boolean isCust() {
        return rawType.hasChild() && ! rawType.isEnum();
    }

    @Override
    public String toString() {
        if(isGeneric()) {
            //maybe List<Integer>
            return rawType.getName() +
                    generics.stream().map(PropertyType::toString).collect(Collectors.joining(",", "<", ">"));
        }
        //default List<T>
        return  rawType.getTypeName();
    }

}