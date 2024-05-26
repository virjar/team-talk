package com.virjar.tk.server.sys.mapper.metric;

import com.virjar.tk.server.sys.entity.metric.SysMetricTag;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 指标tag定义 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysMetricTagMapper extends R2dbcRepository<SysMetricTag, Long> {

}
