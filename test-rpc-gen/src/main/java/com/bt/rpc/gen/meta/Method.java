package com.bt.rpc.gen.meta;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import static com.bt.rpc.gen.meta.Property.DOC_ANNO;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Method implements Comparable<Method>{
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
        ).findFirst().map(it->"// "+(String)it.properties.get("value")).orElse("");
    }

    public boolean isHidden() {
        var hd = null != annotations && annotations.stream().anyMatch(it ->
                DOC_ANNO.equals(it.originName) &&
                        Boolean.TRUE.equals(it.properties.get("hidden"))
        );
        //System.out.println(name + " hidden : " + hd+" " + annotations);
        // dart 端没有处理ByteArray / Uint8List类型的返回
        return hd
                || ( arg!=null && "byte[]".equals(arg.getRawType().originName))
                || "byte[]".equals(res.getRawType().originName)
                ;
    }


    public String dartRes() {
        if("Map".equals(res.getRawType().getName())){
            res.generics.get(1).getRawType().setName("dynamic");
            //return "Map<String,dynamic>";
        }
        return res.toString();
    }

    @Override
    public int compareTo(Method o) {
        return name.compareTo(o.name);
    }
}