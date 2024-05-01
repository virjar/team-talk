/*!40101 SET NAMES utf8 */;
-- auto generated by yint package builder
-- export date: 2023-04-19 10:03:16
-- please contact iinti_cn(wechat) to get business support

create  database teamTalk;
use teamTalk;

-- drop table if exist
drop table if exists metric;
drop table if exists metric_tag;
drop table if exists server_node;
drop table if exists sys_config;
drop table if exists sys_log;
drop table if exists user_info;

-- create tables
CREATE TABLE `metric_day`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`        varchar(64) NOT NULL COMMENT '指标名称',
    `time_key`    varchar(64) NOT NULL COMMENT '时间索引',
    `tags_md5`    varchar(64) NOT NULL COMMENT '对于tag字段自然顺序拼接求md5',
    `tag1`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag2`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag3`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag4`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag5`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `type`        varchar(8)  NOT NULL COMMENT '指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）',
    `value`       double      NOT NULL COMMENT '指标值',
    `create_time` datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`,`tags_md5`,`time_key`),
    KEY           `idx_query` (`name`,`tags_md5`),
    KEY `idx_delete` (`name`,`create_time`) COMMENT '给删除指标使用'
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='监控指标,天级';

CREATE TABLE `metric_hour`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`        varchar(64) NOT NULL COMMENT '指标名称',
    `time_key`    varchar(64) NOT NULL COMMENT '时间索引',
    `tags_md5`    varchar(64) NOT NULL COMMENT '对于tag字段自然顺序拼接求md5',
    `tag1`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag2`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag3`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag4`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag5`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `type`        varchar(8)  NOT NULL COMMENT '指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）',
    `value`       double      NOT NULL COMMENT '指标值',
    `create_time` datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`,`tags_md5`,`time_key`),
    KEY           `idx_query` (`name`,`tags_md5`),
    KEY `idx_delete` (`name`,`create_time`) COMMENT '给删除指标使用'
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='监控指标,小时级';

CREATE TABLE `metric_minute`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`        varchar(64) NOT NULL COMMENT '指标名称',
    `time_key`    varchar(64) NOT NULL COMMENT '时间索引',
    `tags_md5`    varchar(64) NOT NULL COMMENT '对于tag字段自然顺序拼接求md5',
    `tag1`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag2`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag3`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag4`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `tag5`        varchar(64)          DEFAULT NULL COMMENT '指标tag',
    `type`        varchar(8)  NOT NULL COMMENT '指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）',
    `value`       double      NOT NULL COMMENT '指标值',
    `create_time` datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`,`tags_md5`,`time_key`),
    KEY           `idx_query` (`name`,`tags_md5`),
    KEY `idx_delete` (`name`,`create_time`) COMMENT '给删除指标使用'
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='监控指标,分钟级';

CREATE TABLE `metric_tag`
(
    `id`        bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`      varchar(64) NOT NULL COMMENT '指标名称',
    `tag1_name` varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag2_name` varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag3_name` varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag4_name` varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag5_name` varchar(64) DEFAULT NULL COMMENT '指标tag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='指标tag定义';


CREATE TABLE `server_node`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `server_id`        varchar(64)  NOT NULL COMMENT '服务器id，唯一标记服务器',
    `last_active_time` datetime              DEFAULT NULL COMMENT '最后心跳时间',
    `create_time`      datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `ip`               varchar(512) NOT NULL DEFAULT '',
    `port`             int          NOT NULL COMMENT 'springboot 服务开启端口',
    `enable`           tinyint(1) NOT NULL DEFAULT '1' COMMENT '服务器是否启用',
    `local_ip`         varchar(512)           DEFAULT NULL COMMENT '本地IP',
    `out_ip`           varchar(512)           DEFAULT NULL COMMENT '工作ip',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`server_id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='服务器节点，多台服务器组成代理集群';

CREATE TABLE `sys_config`
(
    `id`             bigint NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `config_comment` varchar(128)  DEFAULT NULL COMMENT '配置备注',
    `config_key`     varchar(64)   DEFAULT NULL COMMENT 'key',
    `config_value`   varchar(1024) DEFAULT NULL COMMENT 'value',
    `create_time`    datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uniq_record` (`config_comment` ,`config_key`),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='系统配置';


CREATE TABLE `sys_log`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `username`    varchar(100) DEFAULT NULL COMMENT '操作用户名',
    `operation`   varchar(200) DEFAULT NULL COMMENT '操作',
    `params`      varchar(500) DEFAULT NULL COMMENT ' 操作参数',
    `method_name` varchar(200) DEFAULT NULL COMMENT '操作的方法名',
    `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4;


CREATE TABLE `user_info`
(
    `id`          bigint   NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `user_name`   varchar(64)       DEFAULT NULL COMMENT '用户名',
    `password`    varchar(64)       DEFAULT NULL COMMENT '密码',
    `last_active` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后登陆时间',
    `create_time` datetime          DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `login_token` varchar(64)       DEFAULT NULL COMMENT '登录token',
    `api_token`   varchar(64)       DEFAULT '' COMMENT 'api 访问token',
    `is_admin`    tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否是管理员',
    `update_time` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `permission` varchar(2048)      DEFAULT '' COMMENT '用户权限',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='用户信息';
