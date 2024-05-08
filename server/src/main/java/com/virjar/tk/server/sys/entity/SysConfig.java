package com.virjar.tk.server.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.LocalDateTime;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 系统配置
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_config")
@Schema(name = "SysConfig对象", description = "系统配置")
public class SysConfig implements Serializable {


    @Schema(name = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(name = "key")
    private String configKey;

    @Schema(name = "value")
    private String configValue;

    @Schema(name = "创建时间")
    private LocalDateTime createTime;

    @Schema(name = "配置备注")
    private String configComment;


    public static final String ID = "id";

    public static final String CONFIG_KEY = "config_key";

    public static final String CONFIG_VALUE = "config_value";

    public static final String CREATE_TIME = "create_time";

    public static final String CONFIG_COMMENT = "config_comment";

}
