package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImGroup;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 群组 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImGroupMapper extends R2dbcRepository<ImGroup, Long> {

}
