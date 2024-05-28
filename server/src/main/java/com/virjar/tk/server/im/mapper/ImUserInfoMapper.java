package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImUserInfo;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * <p>
 * 用户信息 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImUserInfoMapper extends R2dbcRepository<ImUserInfo, Long> {
    Mono<ImUserInfo> findByUserNameAndPassword(String userName, String password);

    Mono<ImUserInfo> findByUserName(String userName);

    Mono<Integer> countByUserName(String userName);

    Mono<Integer> countBySysAdmin(Boolean sysAdmin);

}
