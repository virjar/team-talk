package com.virjar.tk.server.im.entity;

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
 * 接入设备
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_device")
@Schema(name = "ImDevice", description = "接入设备")
public class ImDevice implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "平台类型：Android、IOS、PC、WEB")
    private Byte platform;

    @Schema(description = "接入token")
    private String token;

    @Schema(description = "最后接入IP")
    private String lastIp;

    @Schema(description = "设备版本号")
    private String version;

    @Schema(description = "最后接入位置（使用GEO Hash描述）")
    private String locationGeohash;

    @Schema(description = "当前设备状态")
    private Byte status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String USER_ID = "user_id";

    public static final String PLATFORM = "platform";

    public static final String TOKEN = "token";

    public static final String LAST_IP = "last_ip";

    public static final String VERSION = "version";

    public static final String LOCATION_GEOHASH = "location_geohash";

    public static final String STATUS = "status";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}
