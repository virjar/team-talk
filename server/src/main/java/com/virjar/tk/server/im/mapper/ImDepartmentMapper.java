package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImDepartment;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 部门 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImDepartmentMapper extends R2dbcRepository<ImDepartment, Long> {

}
