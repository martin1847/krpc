package com.bt.mybatis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bt.mybatis.sqlsource.CountSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.session.RowBounds;

/**
 * date: 16/6/12 16:01
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
public abstract class AbstractPagingInterceptor implements Interceptor {

    public static final  String                       LIMIT                 = "limit";
    public static final  String                       OFFSET                = "offset";
    //缓存count查询的MappedStatement
    private static final Map<String, MappedStatement> COUNT_STATEMENT_CACHE = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        RowBounds rb = (RowBounds) args[2];

        if (RowBounds.DEFAULT != rb && rb instanceof DbBounds) {

            DbBounds dbBounds = (DbBounds) rb;
            args[2] = RowBounds.DEFAULT;//reset Default 还原RowBounds
            Map parameterObject = (Map) args[1];

            if (dbBounds.isNeedCount()) {
                final MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                args[0] = COUNT_STATEMENT_CACHE.computeIfAbsent(ms.getId(), s ->
                        CountSqlSource.newCountMappedStatement(ms, this::getCountSql));
                //查询总数
                Object result = invocation.proceed();
                dbBounds.setCount((Integer) ((List) result).get(0));
                //还原ms
                args[0] = ms;
            }
            parameterObject.put(OFFSET, dbBounds.getOffset());
            parameterObject.put(LIMIT, dbBounds.getLimit());

        }

        return invocation.proceed();
    }

    protected abstract String getCountSql(String targetSql);

    @Override
    public Object plugin(Object target) {
        if (target instanceof Executor) {//只拦截Executor
            return Plugin.wrap(target, this);
        } else {
            return target;
        }
    }



    //    private static final Field bound_Sql_Field = getField(BoundSql.class,"sql");

    //
    //
    //    public static class ModifBoundSql extends BoundSql{
    //
    //        public ModifBoundSql(Configuration configuration, String sql, List<ParameterMapping> parameterMappings, Object
    // parameterObject) {
    //            super(configuration, sql, parameterMappings, parameterObject);
    //        }
    //    }

}
