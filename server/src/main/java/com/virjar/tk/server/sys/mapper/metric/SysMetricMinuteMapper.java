package com.virjar.tk.server.sys.mapper.metric;

import com.virjar.tk.server.sys.entity.metric.SysMetricMinute;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 监控指标,分钟级 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysMetricMinuteMapper extends R2dbcRepository<SysMetricMinute, Long> {

}
