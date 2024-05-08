create
database team_talk;
use
team_talk;

-- create tables
-- 系统底层服务相关表结构
CREATE TABLE `sys_metric_day`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`        varchar(64) NOT NULL COMMENT '指标名称',
    `time_key`    varchar(64) NOT NULL COMMENT '时间索引',
    `tags_md5`    varchar(64) NOT NULL COMMENT '对于tag字段自然顺序拼接求md5',
    `tag1`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag2`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag3`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag4`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag5`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `type`        varchar(8)  NOT NULL COMMENT '指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）',
    `value` double NOT NULL COMMENT '指标值',
    `create_time` datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`,`tags_md5`,`time_key`),
    KEY           `idx_query` (`name`,`tags_md5`),
    KEY           `idx_delete` (`name`,`create_time`) COMMENT '给删除指标使用'
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='监控指标,天级';

CREATE TABLE `sys_metric_hour`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`        varchar(64) NOT NULL COMMENT '指标名称',
    `time_key`    varchar(64) NOT NULL COMMENT '时间索引',
    `tags_md5`    varchar(64) NOT NULL COMMENT '对于tag字段自然顺序拼接求md5',
    `tag1`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag2`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag3`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag4`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag5`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `type`        varchar(8)  NOT NULL COMMENT '指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）',
    `value` double NOT NULL COMMENT '指标值',
    `create_time` datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`,`tags_md5`,`time_key`),
    KEY           `idx_query` (`name`,`tags_md5`),
    KEY           `idx_delete` (`name`,`create_time`) COMMENT '给删除指标使用'
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='监控指标,小时级';

CREATE TABLE `sys_metric_minute`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `name`        varchar(64) NOT NULL COMMENT '指标名称',
    `time_key`    varchar(64) NOT NULL COMMENT '时间索引',
    `tags_md5`    varchar(64) NOT NULL COMMENT '对于tag字段自然顺序拼接求md5',
    `tag1`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag2`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag3`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag4`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `tag5`        varchar(64) DEFAULT NULL COMMENT '指标tag',
    `type`        varchar(8)  NOT NULL COMMENT '指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）',
    `value` double NOT NULL COMMENT '指标值',
    `create_time` datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_record` (`name`,`tags_md5`,`time_key`),
    KEY           `idx_query` (`name`,`tags_md5`),
    KEY           `idx_delete` (`name`,`create_time`) COMMENT '给删除指标使用'
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='监控指标,分钟级';

CREATE TABLE `sys_metric_tag`
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


CREATE TABLE `sys_server_node`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `server_id`        varchar(64)  NOT NULL COMMENT '服务器id，唯一标记服务器',
    `last_active_time` datetime              DEFAULT NULL COMMENT '最后心跳时间',
    `create_time`      datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `ip`               varchar(512) NOT NULL DEFAULT '',
    `port`             int          NOT NULL COMMENT '服务端口',
    `enable`           tinyint(1) NOT NULL DEFAULT '1' COMMENT '服务器是否启用',
    `local_ip`         varchar(512)          DEFAULT NULL COMMENT '本地IP',
    `out_ip`           varchar(512)          DEFAULT NULL COMMENT '工作ip',
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
    `remote_addr` varchar(255) COMMENT '操作IP地址',
    `method_name` varchar(200) DEFAULT NULL COMMENT '操作的方法名',
    `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4;

CREATE TABLE `sys_file_asset`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `md5`         varchar(128) NOT NULL COMMENT '文件md5',
    `file_length` bigint       NOT NULL COMMENT '文件大小',
    `expire_time` timestamp    NOT NULL COMMENT '过期时间，过期文件将会被删除清理',
    `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4 COMMENT='文件资产';

-- IM聊天核心相关表结构，使用“im_”为前缀
-- IM相关表核心参考了野火IM的数据库设计： https://gitee.com/wfchat/im-server
-- 业务实体设计主要参考悟空IM的设计（强烈推荐悟空IM的，他们的代码质量很高）： https://githubim.com/guide/proto.html
CREATE TABLE `im_user_info`
(
    `id`                 bigint  NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `tk_id`              varchar(64)      DEFAULT NULL COMMENT 'teamTalkId，类似qq号、微信号',
    `user_type`          tinyint          DEFAULT 0 COMMENT '用户类型：普通用户、机器人、平台号等等',
    `user_name`          varchar(64)      DEFAULT NULL COMMENT '用户名',
    `avatar`             varchar(1000) COMMENT '用户头像',
    `gender`             tinyint NOT NULL default 0 COMMENT '性别',
    `password`           varchar(64)      DEFAULT NULL COMMENT '密码',
    `wechat`             varchar(64)      DEFAULT NULL COMMENT '微信号',
    `phone`              varchar(64)      DEFAULT NULL COMMENT '手机号',
    `email`              varchar(64)      DEFAULT NULL COMMENT '邮箱',
    `department_id`      bigint           DEFAULT NULL COMMENT '所属部门id',
    `department_prefix`  varchar(64)      DEFAULT NULL COMMENT '部门前缀',
    `manager`            bigint NULL   COMMENT '直属主管',
    `business_office`    varchar(128)     DEFAULT NULL COMMENT '办公地点（包含工位）',
    `duty`               varchar(128)     DEFAULT NULL COMMENT '职务',
    `job_number`         varchar(64)      DEFAULT NULL COMMENT '工号',
    `last_login`         datetime NULL  COMMENT '最后登陆时间',
    `last_login_ip`      varchar(100) COMMENT '最后登陆IP',
    `web_login_token`    varchar(64)      DEFAULT NULL COMMENT 'web后台登录token',
    `api_token`          varchar(64)      DEFAULT '' COMMENT 'api 访问token',
    `sys_admin`          tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否是系统管理员',
    `robot_secret`       varchar(128) NULL COMMENT '机器人secret（仅机器人存在）',
    `robot_web_hook_url` varchar(128) NULL COMMENT '机器人消息回调接口（仅机器人存在）',
    `user_settings`      varchar(2048) NULL COMMENT '用户设置（如某些群设置免打扰等需要跨设别共享的设置）',
    `create_time`        datetime         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='用户信息';


-- 在企业沟通中，好友，好友分类这些概念应该不强，所以暂时不设计好友相关表
CREATE TABLE `im_device`
(
    `id`               bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `user_id`          bigint      NOT NULL COMMENT '用户id',
    `platform`         tinyint     NOT NULL COMMENT '平台类型：Android、IOS、PC、WEB',
    `token`            varchar(64) NOT NULL COMMENT '接入token',
    `last_ip`          varchar(100) COMMENT '最后接入IP',
    `version`          varchar(64) COMMENT '设备版本号',
    `location_geohash` varchar(128) COMMENT '最后接入位置（使用GEO Hash描述）',
    `status`           tinyint     NOT NULL DEFAULT 0 COMMENT '当前设备状态',
    `create_time`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='接入设备';

CREATE TABLE `im_department`
(
    `id`                bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `parent_id`         bigint      DEFAULT NULL COMMENT '父级',
    `name`              varchar(64) NOT NULL COMMENT '部门名称',
    `primary_admin`     varchar(64) DEFAULT NULL COMMENT '部门主负责人',
    `second_admin`      varchar(64) DEFAULT NULL COMMENT '部门次负责人',
    `department_prefix` varchar(64) DEFAULT NULL COMMENT '部门前缀',
    `create_dep_group`  tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否创建部门群聊（对于集团/公司级别的不能创建大群聊，对于小部门，可以自动创建工作群聊）',
    `created_by`        varchar(64) NOT NULL COMMENT '创建者',
    `create_time`       datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='部门';


CREATE TABLE `im_group`
(
    `id`           bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `group_id`     varchar(64) NOT NULL COMMENT '群组id',
    `group_name`   varchar(64) NOT NULL COMMENT '群组名',
    `avatar`       varchar(1000) COMMENT '群头像',
    `member_count` int      DEFAULT 0 COMMENT '群成员数量，来自离线同步计算',
    `mute`         tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否全员静音',
    `public_group` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否是公开群，公开群可以被搜索到',
    `invitable`    tinyint(1) NOT NULL DEFAULT '0' COMMENT '普通用户是否可以邀请加入本群聊',
    `dep_group`    tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否是部门群组',
    `current_seq`  bigint      NOT NULL COMMENT '当前消息流水号',
    `created_by`   varchar(64) NOT NULL COMMENT '创建者',
    `create_time`  datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='群组';

CREATE TABLE `im_group_member`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `group_id`    bigint NOT NULL COMMENT '群组id',
    `user_id`     bigint NOT NULL COMMENT '用户ID',
    `mute`        tinyint(1) NOT NULL  DEFAULT 0   COMMENT '消息免打扰',
    `pin`         tinyint(1) NOT NULL  DEFAULT 0   COMMENT '聊天置顶',
    `identity`    tinyint     DEFAULT 0 COMMENT '身份（群主，群管理员，普通群员）',
    `alias`       varchar(64) DEFAULT NULL COMMENT '群昵称',
    `status`      tinyint     DEFAULT 0 NULL COMMENT '状态（加入，离开，禁言）',
    `join_time`   datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='群组';


--- 对于企业办公来说，群聊消息多于单聊消息，所以群组消息分表更多
--- im默认配置，群组消息，分10张表。普通用户消息，分4张表。消息分表使用用户id或者群组id来哈希
CREATE TABLE `im_group_msg_1`
(
    `id`          bigint        NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `group_id`    bigint        NOT NULL COMMENT '所属群组',
    `msg_id`      varchar(64)   NOT NULL COMMENT '消息ID',
    `msg_seq`     bigint NULL COMMENT '消息序列号(避免ack，使用seq进行批量对账)',
    `sender`      bigint COMMENT '发送消息用户',
    `ack_user`    bigint COMMENT '已读人数',
    `notice`      tinyint(1) NOT NULL  DEFAULT 0   COMMENT '是否是群公告消息',
    `msg_type`    tinyint       NOT NULL DEFAULT 0 COMMENT '消息类型(文本、代码、富文本、图片及视频、文件、链接、置顶消息、群待办、小程序等)',
    `msg_body`    varchar(4096) NOT NULL COMMENT '消息内容',
    `create_time` datetime               DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='群组消息';

CREATE TABLE `im_msg_1`
(
    `id`          bigint        NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `msg_id`      varchar(64)   NOT NULL COMMENT '消息ID',
    `msg_seq`     bigint NULL COMMENT '消息序列号(避免ack，使用seq进行批量对账)',
    `sender`      bigint COMMENT '发送消息用户',
    `receiver`    bigint COMMENT '接受消息用户',
    `read_ack`    tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已读',
    `msg_type`    tinyint       NOT NULL DEFAULT 0 COMMENT '消息类型(文本、代码、富文本、图片及视频、文件、链接、置顶消息、群待办、小程序等)',
    `msg_body`    varchar(4096) NOT NULL COMMENT '消息内容',
    `create_time` datetime               DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='单聊消息';


CREATE TABLE `im_msg_read_ack`
(
    `id`                bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `user_id`           bigint COMMENT '对应用户',
    `msg_id`            varchar(64) NOT NULL COMMENT '消息ID',
    `conversion_target` varchar(64) NULL COMMENT '对话者，可能是普通用户，也可能是群。',
    `read_ack`          tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已读',
    `open_link`         tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否打开链接（如视频、图片、文档，则是否打开文件）',
    `emoji_ack`         varchar(64) NULL  COMMENT '表情回复，如回复了多个表情，则逗号隔开',
    `create_time`       datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='消息已读记录表';

CREATE TABLE `im_robot_msg_queue`
(
    `id`                bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `msg_id`            varchar(64) NOT NULL COMMENT '消息ID',
    `group_id`          bigint COMMENT '对应群组（说明机器人只能在群聊中存在）',
    `sending_index`     int(3) NOT NULL DEFAULT 0 COMMENT '重试发送索引',
    `expire_time`       timestamp NULL   COMMENT '重试最终超时时间',
    `next_sending_time` timestamp NULL   COMMENT '下次重试发送时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='机器人通知消息队列（考虑可能的失败，机器人消息需要推送重试）';


CREATE TABLE `im_recent_conversion`
(
    `id`                bigint NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `user_id`           bigint NOT NULL COMMENT '用户id',
    `conversion_target` varchar(64) NULL COMMENT '对话者，可能是普通用户，也可能是群。',
    `msg_seq`           bigint COMMENT '当前所有游标',
    `read_seq`          bigint COMMENT '当前已读消息游标',
    `create_time`       datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='最近会话，主要用于同步红点计数';

CREATE TABLE `im_msg_archive`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主建',
    `time_key`    varchar(16)  NOT NULL COMMENT '时间窗口(一个小时一条记录)',
    `oss_path`    varchar(128) NOT NULL COMMENT '归档文件地址',
    `total_count` int          NOT NULL DEFAULT '0' COMMENT '总条目数',
    `deleted`     tinyint(1)               DEFAULT '0' COMMENT '是否被删除（删除过程是逻辑假删，并且清空文件存储。db数据可以继续用来做统计）',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8mb4  COMMENT='消息压缩归档，超过特定时间的历史消息，将会被压缩归档存储';