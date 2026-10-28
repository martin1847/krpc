package tech.krpc.ext.gen.meta;

import java.util.TreeSet;
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
        // GENDET-001: TreeSet (not HashSet) so the generated service-file import list is
        // emitted in a deterministic, name-sorted order regardless of method/scan order.
        Set<String> dtos = new TreeSet<>();
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