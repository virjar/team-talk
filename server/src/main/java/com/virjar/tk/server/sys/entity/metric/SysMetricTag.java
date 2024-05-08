package com.virjar.tk.server.sys.entity.metric;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 指标tag定义
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
@Getter
@Setter
@TableName("sys_metric_tag")
@Schema(name = "SysMetricTag", description = "指标tag定义")
public class SysMetricTag implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "指标名称")
    private String name;

    @Schema(description = "指标tag")
    private String tag1Name;

    @Schema(description = "指标tag")
    private String tag2Name;

    @Schema(description = "指标tag")
    private String tag3Name;

    @Schema(description = "指标tag")
    private String tag4Name;

    @Schema(description = "指标tag")
    private String tag5Name;

    public static final String ID = "id";

    public static final String NAME = "name";

    public static final String TAG1_NAME = "tag1_name";

    public static final String TAG2_NAME = "tag2_name";

    public static final String TAG3_NAME = "tag3_name";

    public static final String TAG4_NAME = "tag4_name";

    public static final String TAG5_NAME = "tag5_name";
}
