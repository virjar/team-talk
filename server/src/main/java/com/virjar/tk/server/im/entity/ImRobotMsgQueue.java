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
 * 机器人通知消息队列（考虑可能的失败，机器人消息需要推送重试）
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_robot_msg_queue")
@Schema(name = "ImRobotMsgQueue", description = "机器人通知消息队列（考虑可能的失败，机器人消息需要推送重试）")
public class ImRobotMsgQueue implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "消息ID")
    private String msgId;

    @Schema(description = "对应群组（说明机器人只能在群聊中存在）")
    private Long groupId;

    @Schema(description = "重试发送索引")
    private Integer sendingIndex;

    @Schema(description = "重试最终超时时间")
    private LocalDateTime expireTime;

    @Schema(description = "下次重试发送时间")
    private LocalDateTime nextSendingTime;

    public static final String ID = "id";

    public static final String MSG_ID = "msg_id";

    public static final String GROUP_ID = "group_id";

    public static final String SENDING_INDEX = "sending_index";

    public static final String EXPIRE_TIME = "expire_time";

    public static final String NEXT_SENDING_TIME = "next_sending_time";
}
