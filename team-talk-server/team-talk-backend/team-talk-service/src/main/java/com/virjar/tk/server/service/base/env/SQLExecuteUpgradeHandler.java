package com.virjar.tk.server.service.base.env;

import com.virjar.tk.server.utils.ResourceUtil;
import com.google.common.base.Splitter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;


/**
 * 执行一段sql文件，请注意模块鲁棒性不强，sql使用"；"分割，不支持注释
 */
public class SQLExecuteUpgradeHandler implements UpgradeHandler {
    private final String sqlClassPath;

    public SQLExecuteUpgradeHandler(String sqlClassPath) {
        this.sqlClassPath = sqlClassPath;
    }

    @Override
    public void doDbUpgrade(DataSource dataSource) throws SQLException {
        String sqlData = ResourceUtil.readText(sqlClassPath);
        try (Connection connection = dataSource.getConnection()) {
            List<String> sqlStatementList = Splitter.on(';').omitEmptyStrings().trimResults().splitToList(sqlData);
            for (String sql : sqlStatementList) {
                try (Statement statement = connection.createStatement()) {
                    System.out.println("execute sql:" + sql);
                    statement.execute(sql);
                }
            }
        }
    }
}
