package com.bt.mybatis.type;

import java.util.List;

import org.apache.ibatis.type.MappedTypes;

/**
 * date: 16/8/15 17:11
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
@MappedTypes(List.class)
public class Json2IntList extends JsonListHandler<Integer> {
}
