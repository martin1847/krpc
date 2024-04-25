/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.common.meta;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 *
 * @author Martin.C
 * @version 2021/11/04 11:25 AM
 */
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
@Data
public class Anno {

    String name;
    Map<String, Object> properties;

    public Anno(String name, Map<String, Object> properties) {
        this.name = name;
        this.properties = properties;
    }

}