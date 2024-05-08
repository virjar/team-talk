package com.virjar.tk.server.sys.service.env;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 版本升级管理器，用于版本迭代
 */
public interface UpgradeHandler {
    /**
     * 当对于数据库的升级过程，由于数据库一般是中心形态，所以数据库升级应该只执行一次
     */
    default void doDbUpgrade(DataSource dataSource) throws SQLException {
    }


    /**
     * 对于本地资产的升级，此升级对于每个机器（节点）都需要执行一遍
     * 请注意，对于docker容器执行的情况，由于每次重启docker数据都会被清空，所以docker环境下一般不需要考虑此流程
     */
    default void doLocalUpgrade() {
    }
}
