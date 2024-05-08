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
 * 消息已读记录表
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_msg_read_ack")
@Schema(name = "ImMsgReadAck", description = "消息已读记录表")
public class ImMsgReadAck implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "对应用户")
    private Long userId;

    @Schema(description = "消息ID")
    private String msgId;

    @Schema(description = "对话者，可能是普通用户，也可能是群。")
    private String conversionTarget;

    @Schema(description = "是否已读")
    private Boolean readAck;

    @Schema(description = "是否打开链接（如视频、图片、文档，则是否打开文件）")
    private Boolean openLink;

    @Schema(description = "表情回复，如回复了多个表情，则逗号隔开")
    private String emojiAck;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String USER_ID = "user_id";

    public static final String MSG_ID = "msg_id";

    public static final String CONVERSION_TARGET = "conversion_target";

    public static final String READ_ACK = "read_ack";

    public static final String OPEN_LINK = "open_link";

    public static final String EMOJI_ACK = "emoji_ack";

    public static final String CREATE_TIME = "create_time";
}
