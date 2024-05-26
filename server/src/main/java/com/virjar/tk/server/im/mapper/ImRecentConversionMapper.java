package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImRecentConversion;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 最近会话，主要用于同步红点计数 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImRecentConversionMapper extends R2dbcRepository<ImRecentConversion, Long> {

}
