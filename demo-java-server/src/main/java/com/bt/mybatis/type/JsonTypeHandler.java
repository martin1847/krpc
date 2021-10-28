package com.bt.mybatis.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.bt.rpc.util.JsonUtils;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 *
 * json字符串通过mybatis自动转换为对象.
 * 子类继承务必指定范型,即:把Json字符串转换为何等类型.
 * 否则转换无法进行.
 *
 * date: 16/6/8 16:18
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
public abstract class JsonTypeHandler<T> extends BaseTypeHandler<T> {

    private final Class<T> tClass;

    {
        Type[] pTypes = getParameterizedTypes(this);
        tClass = (Class<T>) pTypes[0];
    }

    public static Type[] getParameterizedTypes(Object object) {
        Type superclassType = object.getClass().getGenericSuperclass();
        if (!ParameterizedType.class.isAssignableFrom(superclassType.getClass())) {
            return null;
        }
        return ((ParameterizedType) superclassType).getActualTypeArguments();
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, JsonUtils.stringify(parameter));
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJSON(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJSON(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJSON(cs.getString(columnIndex));
    }

    protected T parseJSON(String json) {
        return JsonUtils.parse(json, tClass);
    }
}
