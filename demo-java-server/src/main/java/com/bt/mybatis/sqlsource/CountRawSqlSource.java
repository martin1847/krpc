package com.bt.mybatis.sqlsource;

import java.util.function.Function;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.defaults.RawSqlSource;

public class CountRawSqlSource extends CountSqlSource {
    protected CountStaticSqlSource sqlSource;

    @SuppressWarnings("unchecked")
    public CountRawSqlSource(RawSqlSource sqlSource, Function<String, String> toCount) {
        MetaObject metaObject = SystemMetaObject.forObject(sqlSource);
        this.sqlSource = new CountStaticSqlSource(
                (StaticSqlSource) metaObject.getValue("sqlSource"), toCount);
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        return sqlSource.getBoundSql(parameterObject);
    }
}
