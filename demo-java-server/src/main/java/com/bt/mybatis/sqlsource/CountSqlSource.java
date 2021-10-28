package com.bt.mybatis.sqlsource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bt.mybatis.AbstractPagingInterceptor;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;

/**
 * date: 16/11/9 14:59
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
public abstract class CountSqlSource implements SqlSource {

    /**
     * 新建count查询和分页查询的MappedStatement
     */
    public static MappedStatement newCountMappedStatement(MappedStatement ms, Function<String, String> toCount) {

        //        System.out.println("Make countSQL with Id : " + ms.getId());

        final SqlSource origin = ms.getSqlSource();
        SqlSource newStaticSql = toCountSql(origin, toCount);

        //
        //        origin instanceof DynamicContext
        MappedStatement.Builder builder = new MappedStatement.Builder(ms.getConfiguration(),
                ms.getId() + "_AUTO_COUNT_", newStaticSql, ms.getSqlCommandType());
        builder.resource(ms.getResource());
        builder.fetchSize(ms.getFetchSize());
        builder.statementType(ms.getStatementType());
        builder.keyGenerator(ms.getKeyGenerator());
        if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
            StringBuilder keyProperties = new StringBuilder();
            for (String keyProperty : ms.getKeyProperties()) {
                keyProperties.append(keyProperty).append(",");
            }
            keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
            builder.keyProperty(keyProperties.toString());
        }
        builder.timeout(ms.getTimeout());
        builder.parameterMap(ms.getParameterMap());
        //count查询返回值int
        List<ResultMap> resultMaps = new ArrayList<ResultMap>();
        resultMaps.add(new ResultMap.Builder(ms.getConfiguration(), ms.getId(),
                int.class, Collections.emptyList()).build());
        builder.resultMaps(resultMaps);
        builder.resultSetType(ms.getResultSetType());
        builder.cache(ms.getCache());
        builder.flushCacheRequired(ms.isFlushCacheRequired());
        builder.useCache(ms.isUseCache());

        return builder.build();
    }

    private static SqlSource toCountSql(SqlSource sqlSource, Function<String, String> toCount) {
        if (sqlSource instanceof StaticSqlSource) {
            return new CountStaticSqlSource((StaticSqlSource) sqlSource, toCount);
        } else if (sqlSource instanceof DynamicSqlSource) {
            return new CountDynamicSqlSource((DynamicSqlSource) sqlSource, toCount);
        } else if (sqlSource instanceof RawSqlSource) {
            return new CountRawSqlSource((RawSqlSource) sqlSource, toCount);
            //        } else if (sqlSource instanceof ProviderSqlSource) {
            //            return new PageProviderSqlSource((ProviderSqlSource) sqlSource);
        } else {
            throw new RuntimeException("无法处理该类型[" + sqlSource.getClass() + "]的SqlSource");
        }
    }

    protected List<ParameterMapping> removeLimitOffset(List<ParameterMapping> origin) {
        return origin.stream().filter(it -> {
            String prop = it.getProperty();
            return !AbstractPagingInterceptor.OFFSET.equals(prop) && !AbstractPagingInterceptor.LIMIT.equals(prop);
        }).collect(Collectors.toList());
    }

}
