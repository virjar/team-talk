package com.virjar.tk.server.sys.entity.metric;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.micrometer.core.instrument.Meter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

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
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "Metric对象", description = "监控指标")
public class SysMetric implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "指标名称")
    private String name;

    @Schema(description = "时间索引")
    private String timeKey;

    @Schema(description = "对于tag字段自然顺序拼接求md5")
    private String tagsMd5;

    @Schema(description = "指标tag")
    private String tag1;

    @Schema(description = "指标tag")
    private String tag2;

    @Schema(description = "指标tag")
    private String tag3;

    @Schema(description = "指标tag")
    private String tag4;

    @Schema(description = "指标tag")
    private String tag5;

    @Schema(description = "指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）")
    private Meter.Type type;

    @Schema(description = "指标值")
    private Double value;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String NAME = "name";

    public static final String TIME_KEY = "time_key";

    public static final String TAGS_MD5 = "tags_md5";

    public static final String TAG1 = "tag1";

    public static final String TAG2 = "tag2";

    public static final String TAG3 = "tag3";

    public static final String TAG4 = "tag4";

    public static final String TAG5 = "tag5";

    public static final String TYPE = "type";

    public static final String VALUE = "value";

    public static final String CREATE_TIME = "create_time";
}
