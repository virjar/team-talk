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
 * 单聊消息
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_msg_1")
@Schema(name = "ImMsg1", description = "单聊消息")
public class ImMsg1 implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "消息ID")
    private String msgId;

    @Schema(description = "消息序列号(避免ack，使用seq进行批量对账)")
    private Long msgSeq;

    @Schema(description = "发送消息用户")
    private Long sender;

    @Schema(description = "接受消息用户")
    private Long receiver;

    @Schema(description = "是否已读")
    private Boolean readAck;

    @Schema(description = "消息类型(文本、代码、富文本、图片及视频、文件、链接、置顶消息、群待办、小程序等)")
    private Byte msgType;

    @Schema(description = "消息内容")
    private String msgBody;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String MSG_ID = "msg_id";

    public static final String MSG_SEQ = "msg_seq";

    public static final String SENDER = "sender";

    public static final String RECEIVER = "receiver";

    public static final String READ_ACK = "read_ack";

    public static final String MSG_TYPE = "msg_type";

    public static final String MSG_BODY = "msg_body";

    public static final String CREATE_TIME = "create_time";
}
