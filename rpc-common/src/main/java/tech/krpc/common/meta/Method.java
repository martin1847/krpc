package tech.krpc.common.meta;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class Method {
    String name;
    PropertyType arg;
    PropertyType res;

    List<Anno> annotations;

    //
    //@JsonIgnore
    //public String getDoc(){
    //    if(null == annotations){
    //        return "";
    //    }
    //    return annotations.stream().filter(it ->
    //            DOC_ANNO.equals(it.clz)
    //    ).findFirst().map(it->"/// " +it.properties.get("value")).orElse("");
    //}
    //
    //@JsonIgnore
    //public boolean isHidden() {
    //    var hd = null != annotations && annotations.stream().anyMatch(it ->
    //            DOC_ANNO == it.clz &&
    //                    Boolean.TRUE.equals(it.properties.get("hidden"))
    //    );
    //    //System.out.println(name + " hidden : " + hd+" " + annotations);
    //    return hd || ( arg!=null && "byte[]".equals(arg.getRawType().name));
    //}
    //
    //@JsonIgnore
    //public boolean isCustomerInput() {
    //    return null!=arg && arg.rawType.hasChild();
    //}
    //
    //@JsonIgnore
    //public boolean isCustomerRes() {
    //    return res.rawType.hasChild();
    //}
    //
    //@JsonIgnore
    //public String dartRes() {
    //    if("List".equals(res.getRawType().getName())){
    //        return "List<dynamic>";
    //    }
    //    if("Map".equals(res.getRawType().getName())){
    //        return "Map<String,dynamic>";
    //    }
    //    if("byte[]".equals(res.getRawType().getName())){
    //        return "List<int>";
    //    }
    //    return res.toString();
    //}

}