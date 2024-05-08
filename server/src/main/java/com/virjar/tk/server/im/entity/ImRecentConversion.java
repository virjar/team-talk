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
 * 最近会话，主要用于同步红点计数
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_recent_conversion")
@Schema(name = "ImRecentConversion", description = "最近会话，主要用于同步红点计数")
public class ImRecentConversion implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "对话者，可能是普通用户，也可能是群。")
    private String conversionTarget;

    @Schema(description = "当前所有游标")
    private Long msgSeq;

    @Schema(description = "当前已读消息游标")
    private Long readSeq;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String USER_ID = "user_id";

    public static final String CONVERSION_TARGET = "conversion_target";

    public static final String MSG_SEQ = "msg_seq";

    public static final String READ_SEQ = "read_seq";

    public static final String CREATE_TIME = "create_time";
}
