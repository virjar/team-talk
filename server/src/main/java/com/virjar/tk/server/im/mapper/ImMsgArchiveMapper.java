package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImMsgArchive;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 消息压缩归档，超过特定时间的历史消息，将会被压缩归档存储 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImMsgArchiveMapper extends R2dbcRepository<ImMsgArchive, Long> {

}
