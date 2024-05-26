package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImMsgReadAck;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 消息已读记录表 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImMsgReadAckMapper extends R2dbcRepository<ImMsgReadAck, Long> {

}
