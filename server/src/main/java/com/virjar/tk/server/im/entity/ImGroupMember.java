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
 * 群组
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_group_member")
@Schema(name = "ImGroupMember", description = "群组")
public class ImGroupMember implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "群组id")
    private Long groupId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "消息免打扰")
    private Boolean mute;

    @Schema(description = "聊天置顶")
    private Boolean pin;

    @Schema(description = "身份（群主，群管理员，普通群员）")
    private Byte identity;

    @Schema(description = "群昵称")
    private String alias;

    @Schema(description = "状态（加入，离开，禁言）")
    private Byte status;

    @Schema(description = "创建时间")
    private LocalDateTime joinTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String GROUP_ID = "group_id";

    public static final String USER_ID = "user_id";

    public static final String MUTE = "mute";

    public static final String PIN = "pin";

    public static final String IDENTITY = "identity";

    public static final String ALIAS = "alias";

    public static final String STATUS = "status";

    public static final String JOIN_TIME = "join_time";

    public static final String UPDATE_TIME = "update_time";
}
