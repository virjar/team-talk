package com.virjar.tk.server.sys.mapper.metric;

import com.virjar.tk.server.sys.entity.metric.SysMetricHour;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 监控指标,小时级 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysMetricHourMapper extends R2dbcRepository<SysMetricHour, Long> {

}
