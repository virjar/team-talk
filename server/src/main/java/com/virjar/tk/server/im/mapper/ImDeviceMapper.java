package com.virjar.tk.server.im.mapper;

import com.virjar.tk.server.im.entity.ImDevice;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 接入设备 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
public interface ImDeviceMapper extends R2dbcRepository<ImDevice, Long> {

}
