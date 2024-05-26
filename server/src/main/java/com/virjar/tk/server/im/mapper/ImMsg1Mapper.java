package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImMsg1;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 单聊消息 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImMsg1Mapper extends R2dbcRepository<ImMsg1, Long> {

}
