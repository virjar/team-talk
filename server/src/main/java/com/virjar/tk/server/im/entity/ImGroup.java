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
@TableName("im_group")
@Schema(name = "ImGroup", description = "群组")
public class ImGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "群组id")
    private String groupId;

    @Schema(description = "群组名")
    private String groupName;

    @Schema(description = "群头像")
    private String avatar;

    @Schema(description = "群成员数量，来自离线同步计算")
    private Integer memberCount;

    @Schema(description = "是否全员静音")
    private Boolean mute;

    @Schema(description = "是否是公开群，公开群可以被搜索到")
    private Boolean publicGroup;

    @Schema(description = "普通用户是否可以邀请加入本群聊")
    private Boolean invitable;

    @Schema(description = "是否是部门群组")
    private Boolean depGroup;

    @Schema(description = "当前消息流水号")
    private Long currentSeq;

    @Schema(description = "创建者")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String GROUP_ID = "group_id";

    public static final String GROUP_NAME = "group_name";

    public static final String AVATAR = "avatar";

    public static final String MEMBER_COUNT = "member_count";

    public static final String MUTE = "mute";

    public static final String PUBLIC_GROUP = "public_group";

    public static final String INVITABLE = "invitable";

    public static final String DEP_GROUP = "dep_group";

    public static final String CURRENT_SEQ = "current_seq";

    public static final String CREATED_BY = "created_by";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}
