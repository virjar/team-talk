package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImGroupMember;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 群组 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImGroupMemberMapper extends R2dbcRepository<ImGroupMember, Long> {

}
