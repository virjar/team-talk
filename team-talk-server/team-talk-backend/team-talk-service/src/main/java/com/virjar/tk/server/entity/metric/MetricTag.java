package com.virjar.tk.server.entity.metric;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.io.Serializable;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 指标tag定义
 * </p>
 *
 * @author iinti
 * @since 2022-12-15
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("metric_tag")
@ApiModel(value = "MetricTag对象", description = "指标tag定义")
public class MetricTag implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "指标名称")
    private String name;

    @ApiModelProperty(value = "指标tag")
    private String tag1Name;

    @ApiModelProperty(value = "指标tag")
    private String tag2Name;

    @ApiModelProperty(value = "指标tag")
    private String tag3Name;

    @ApiModelProperty(value = "指标tag")
    private String tag4Name;

    @ApiModelProperty(value = "指标tag")
    private String tag5Name;

    public static final String ID = "id";

    public static final String NAME = "name";

    public static final String TAG1_NAME = "tag1_name";

    public static final String TAG2_NAME = "tag2_name";

    public static final String TAG3_NAME = "tag3_name";

    public static final String TAG4_NAME = "tag4_name";

    public static final String TAG5_NAME = "tag5_name";
}
