package com.virjar.tk.server.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 *
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_log")
@Schema(name = "SysLog对象", description = "")
public class SysLog implements Serializable {

    @Schema(name = "自增主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(name = "操作用户名")
    private String username;

    @Schema(name = "操作")
    private String operation;

    @Schema(name =" 操作参数")
    private String params;

    @Schema(name = "操作的方法名")
    private String methodName;

    @Schema(name = "创建时间")
    private LocalDateTime createTime;


    public static final String ID = "id";

    public static final String USERNAME = "username";

    public static final String OPERATION = "operation";

    public static final String PARAMS = "params";

    public static final String METHOD_NAME = "method_name";

    public static final String CREATE_TIME = "create_time";

}
