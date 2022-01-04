package com.btyx.rpc.gen.meta;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import static com.btyx.rpc.gen.meta.Property.DOC_ANNO;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Method {
    String       name;
    PropertyType arg;
    PropertyType res;

    List<Anno> annotations;


    public String getDoc(){
        if(null == annotations){
            return "";
        }
        return annotations.stream().filter(it ->
                DOC_ANNO.equals(it.originName)
        ).findFirst().map(it->"/// " +it.properties.get("value")).orElse("");
    }

    public boolean isHidden() {
        var hd = null != annotations && annotations.stream().anyMatch(it ->
                DOC_ANNO.equals(it.originName) &&
                        Boolean.TRUE.equals(it.properties.get("hidden"))
        );
        //System.out.println(name + " hidden : " + hd+" " + annotations);
        return hd || ( arg!=null && "byte[]".equals(arg.getRawType().originName));
    }

    //public boolean isCustomerInput() {
    //    return null!=arg && arg.rawType.hasChild();
    //}

    //public boolean isCustomerRes() {
    //    return res.rawType.hasChild();
    //}

    public String dartRes() {
        if("List".equals(res.getRawType().getName())){
            res.generics.get(0).getRawType().setName("dynamic");
            //return "List<dynamic>";
        }else if("Map".equals(res.getRawType().getName())){
            res.generics.get(1).getRawType().setName("dynamic");
            //return "Map<String,dynamic>";
        }
        return res.toString();
    }

}