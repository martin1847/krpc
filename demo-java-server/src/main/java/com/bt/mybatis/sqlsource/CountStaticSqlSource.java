package com.bt.mybatis.sqlsource;

import java.util.List;
import java.util.function.Function;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;

public class CountStaticSqlSource extends CountSqlSource {
    protected String                 sql;
    protected List<ParameterMapping> parameterMappings;
    protected Configuration          configuration;

    @SuppressWarnings("unchecked")
    public CountStaticSqlSource(StaticSqlSource sqlSource, Function<String, String> toCount) {
        MetaObject metaObject = SystemMetaObject.forObject(sqlSource);
        String originSql = (String) metaObject.getValue("sql");
        this.configuration = (Configuration) metaObject.getValue("configuration");
        this.sql = toCount.apply(originSql);
        this.parameterMappings = removeLimitOffset((List<ParameterMapping>) metaObject.getValue("parameterMappings"));
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        return new BoundSql(configuration, sql, parameterMappings, parameterObject);
    }
}
