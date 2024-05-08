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
 * 群组消息
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_group_msg_1")
@Schema(name = "ImGroupMsg1", description = "群组消息")
public class ImGroupMsg1 implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属群组")
    private Long groupId;

    @Schema(description = "消息ID")
    private String msgId;

    @Schema(description = "消息序列号(避免ack，使用seq进行批量对账)")
    private Long msgSeq;

    @Schema(description = "发送消息用户")
    private Long sender;

    @Schema(description = "已读人数")
    private Long ackUser;

    @Schema(description = "是否是群公告消息")
    private Boolean notice;

    @Schema(description = "消息类型(文本、代码、富文本、图片及视频、文件、链接、置顶消息、群待办、小程序等)")
    private Byte msgType;

    @Schema(description = "消息内容")
    private String msgBody;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String GROUP_ID = "group_id";

    public static final String MSG_ID = "msg_id";

    public static final String MSG_SEQ = "msg_seq";

    public static final String SENDER = "sender";

    public static final String ACK_USER = "ack_user";

    public static final String NOTICE = "notice";

    public static final String MSG_TYPE = "msg_type";

    public static final String MSG_BODY = "msg_body";

    public static final String CREATE_TIME = "create_time";
}
