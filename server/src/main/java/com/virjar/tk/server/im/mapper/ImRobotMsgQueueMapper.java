package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImRobotMsgQueue;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 机器人通知消息队列（考虑可能的失败，机器人消息需要推送重试） Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImRobotMsgQueueMapper extends R2dbcRepository<ImRobotMsgQueue, Long> {

}
