package com.bt.mybatis;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * date: 16/6/12 16:27
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
@Intercepts(
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class,
                        RowBounds.class, ResultHandler.class})
)
public class MysqlPagingInterceptor extends AbstractPagingInterceptor {

    public static final String ORDER_BY = "order by";

    public static final String UNION = "union";
    public static final String LIMIT = "limit";
    public static final String FROM  = "from";

    @Override
    protected String getCountSql(String targetSql) {
        String sql = targetSql.toLowerCase();
        StringBuilder sqlBuilder = new StringBuilder(sql);

        int orderByPos = 0;
        if ((orderByPos = sqlBuilder.lastIndexOf(ORDER_BY)) != -1 || (orderByPos = sqlBuilder.lastIndexOf(LIMIT)) != -1) {
            sqlBuilder.delete(orderByPos, sqlBuilder.length());
        }

        if (sqlBuilder.indexOf(UNION) != -1) {
            sqlBuilder.insert(0, "select count(*) from ( ").append(" ) ");
            return sqlBuilder.toString();
        }

        int fromPos = sqlBuilder.indexOf(FROM);
        if (fromPos != -1) {
            sqlBuilder.delete(0, fromPos);
            sqlBuilder.insert(0, "select count(*) ");
        }

        return sqlBuilder.toString();
    }

    //    @Override
    //    protected String getPagingSql(String targetSql, DbBounds dbBounds) {
    //        return targetSql +" LIMIT "+dbBounds.getOffset()+","+dbBounds.getLimit();
    //    }
}
