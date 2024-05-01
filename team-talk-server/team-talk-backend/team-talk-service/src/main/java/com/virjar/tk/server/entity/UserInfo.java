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
 * 用户信息
 * </p>
 *
 * @author iinti
 * @since 2022-12-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_info")
@ApiModel(value = "UserInfo对象", description = "用户信息")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "用户名")
    private String userName;

    @ApiModelProperty(value = "密码")
    private String password;

    @ApiModelProperty(value = "最后登陆时间")
    private LocalDateTime lastActive;

    @ApiModelProperty(value = "创建时间")
    private LocalDateTime createTime;

    @ApiModelProperty(value = "登录token")
    private String loginToken;

    @ApiModelProperty(value = "api 访问token")
    private String apiToken;

    @ApiModelProperty(value = "是否是管理员")
    private Boolean isAdmin;

    @ApiModelProperty(value = "最后更新时间")
    private LocalDateTime updateTime;

    @ApiModelProperty(value = "用户权限")
    private String permission;

    public static final String ID = "id";

    public static final String USER_NAME = "user_name";

    public static final String PASSWORD = "password";

    public static final String LAST_ACTIVE = "last_active";

    public static final String CREATE_TIME = "create_time";

    public static final String LOGIN_TOKEN = "login_token";

    public static final String API_TOKEN = "api_token";

    public static final String IS_ADMIN = "is_admin";

    public static final String UPDATE_TIME = "update_time";

    public static final String PERMISSION = "permission";

}
