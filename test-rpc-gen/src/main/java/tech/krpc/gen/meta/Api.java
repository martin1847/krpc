package tech.krpc.gen.meta;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.Data;
import lombok.NoArgsConstructor;

//暴露出的RpcService
@Data
@NoArgsConstructor
public class Api {
    String       name;
    List<Method> methods;

    String description;


    public Api(String name, List<Method> methods, String description) {
        this.name = name.substring(name.indexOf('/') + 1);
        this.methods = methods;
        this.description = description;
    }


    public Set<String> getCustomerDtos(){
        Set<String> dtos = new HashSet<>();
        methods.forEach(m->{
            addDtos(dtos,m.arg);
            addDtos(dtos,m.res);
        });
        return dtos;
    }

    void addDtos(Set<String> dtos, PropertyType t){
        if(null == t){
            return;
        }
        var dto = t.getRawType();
        if(dto.hasChild()){
            dtos.add(dto.name);
        }
        var types = t.generics;
        if(null != types ){
            for (var ct : types){
                addDtos(dtos,ct);
            }
        }
    }
}