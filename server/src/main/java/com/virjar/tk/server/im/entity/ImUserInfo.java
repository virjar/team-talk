package com.virjar.tk.server.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 用户信息
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_user_info")
@Schema(name = "ImUserInfo", description = "用户信息")
public class ImUserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "teamTalkId，类似qq号、微信号")
    private String tkId;

    @Schema(description = "用户类型：普通用户、机器人、平台号等等")
    private Byte userType;

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "用户头像")
    private String avatar;

    @Schema(description = "性别")
    private Byte gender;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "微信号")
    private String wechat;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "所属部门id")
    private Long departmentId;

    @Schema(description = "部门前缀")
    private String departmentPrefix;

    @Schema(description = "直属主管")
    private Long manager;

    @Schema(description = "办公地点（包含工位）")
    private String businessOffice;

    @Schema(description = "职务")
    private String duty;

    @Schema(description = "工号")
    private String jobNumber;

    @Schema(description = "最后登陆时间")
    private LocalDateTime lastLogin;

    @Schema(description = "最后登陆IP")
    private String lastLoginIp;

    @Schema(description = "web后台登录token")
    private String webLoginToken;

    @Schema(description = "api 访问token")
    private String apiToken;

    @Schema(description = "是否是系统管理员")
    private Boolean sysAdmin;

    @Schema(description = "机器人secret（仅机器人存在）")
    private String robotSecret;

    @Schema(description = "机器人消息回调接口（仅机器人存在）")
    private String robotWebHookUrl;

    @Schema(description = "用户设置（如某些群设置免打扰等需要跨设别共享的设置）")
    private String userSettings;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String TK_ID = "tk_id";

    public static final String USER_TYPE = "user_type";

    public static final String USER_NAME = "user_name";

    public static final String AVATAR = "avatar";

    public static final String GENDER = "gender";

    public static final String PASSWORD = "password";

    public static final String WECHAT = "wechat";

    public static final String PHONE = "phone";

    public static final String EMAIL = "email";

    public static final String DEPARTMENT_ID = "department_id";

    public static final String DEPARTMENT_PREFIX = "department_prefix";

    public static final String MANAGER = "manager";

    public static final String BUSINESS_OFFICE = "business_office";

    public static final String DUTY = "duty";

    public static final String JOB_NUMBER = "job_number";

    public static final String LAST_LOGIN = "last_login";

    public static final String LAST_LOGIN_IP = "last_login_ip";

    public static final String WEB_LOGIN_TOKEN = "web_login_token";

    public static final String API_TOKEN = "api_token";

    public static final String SYS_ADMIN = "sys_admin";

    public static final String ROBOT_SECRET = "robot_secret";

    public static final String ROBOT_WEB_HOOK_URL = "robot_web_hook_url";

    public static final String USER_SETTINGS = "user_settings";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}
