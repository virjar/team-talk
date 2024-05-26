package com.virjar.tk.server.sys.mapper;

import com.virjar.tk.server.sys.entity.SysConfig;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

/**
 * <p>
 * 系统配置 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysConfigMapper extends R2dbcRepository<SysConfig, Long> {
    Flux<SysConfig> findAllByConfigComment(String configComment);
}
