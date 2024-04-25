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
public class Property {
    /// name,may be null
    String name;

    /// type
    PropertyType type;

    /// GetCustomAttributesData
    List<Anno> annotations;


    //let list: Array<number> = [1, 2, 3];
    //List<int> Dart
}