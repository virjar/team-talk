package com.virjar.tk.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.LocalDateTime;
import java.io.Serializable;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 *
 * </p>
 *
 * @author iinti
 * @since 2022-12-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_log")
@ApiModel(value = "SysLog对象", description = "")
public class SysLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "自增主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "操作用户名")
    private String username;

    @ApiModelProperty(value = "操作")
    private String operation;

    @ApiModelProperty(value = " 操作参数")
    private String params;

    @ApiModelProperty(value = "操作的方法名")
    private String methodName;

    @ApiModelProperty(value = "创建时间")
    private LocalDateTime createTime;


    public static final String ID = "id";

    public static final String USERNAME = "username";

    public static final String OPERATION = "operation";

    public static final String PARAMS = "params";

    public static final String METHOD_NAME = "method_name";

    public static final String CREATE_TIME = "create_time";

}
