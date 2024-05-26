package com.virjar.tk.server.sys.mapper;

import com.virjar.tk.server.sys.entity.SysLog;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysLogMapper extends R2dbcRepository<SysLog,Long> {

}
