package com.virjar.tk.server.sys.mapper;

import com.virjar.tk.server.sys.entity.SysServerNode;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

/**
 * <p>
 * 服务器节点，多台服务器组成代理集群 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysServerNodeMapper extends R2dbcRepository<SysServerNode, Long> {

    public Mono<SysServerNode> findByServerId(String serverId);
}
