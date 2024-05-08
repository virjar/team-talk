package com.virjar.tk.server.sys.entity.metric;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 监控指标,天级
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
@Getter
@Setter
@TableName("sys_metric_day")
@Schema(name = "SysMetricDay", description = "监控指标,天级")
public class SysMetricDay extends SysMetric {

}
