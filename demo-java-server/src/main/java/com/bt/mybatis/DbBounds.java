package com.bt.mybatis;

import org.apache.ibatis.session.RowBounds;

/**
 *
 * 扩展 Mybatis 使用数据库,物理分页组件.
 * 配合 {@link MysqlPagingInterceptor}
 *
 * date: 16/6/12 16:17
 *
 * @author: yangyang.cyy@alibaba-inc.com
 */
public class DbBounds extends RowBounds {

    //起始位置
    private int     offset;
    //多少行
    private int     limit;
    //总数
    private int     count;
    //是否需要执行一次Count查询
    private boolean needCount;

    public DbBounds() {}

    public DbBounds(int offset, int limit) {
        this.offset = offset;
        this.limit = limit;
    }

    public DbBounds(int offset, int limit, boolean needCount) {
        this(offset, limit);
        this.needCount = needCount;
    }

    /**
     * 根据前端传递的page pageSize构建分页查询
     */
    public static DbBounds fromPage(int page, int pageSize) {
        pageSize = Math.max(1, pageSize);
        return new DbBounds(Math.max(0, (--page) * pageSize), pageSize, true);
    }

    public boolean isNeedCount() {
        return needCount;
    }

    public void setNeedCount(boolean needCount) {
        this.needCount = needCount;
    }

    @Override
    public int getOffset() {
        return offset;
    }

    @Override
    public int getLimit() {
        return limit;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

}
