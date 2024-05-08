package com.virjar.tk.server.sys.entity;

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
 * 文件资产
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
@Getter
@Setter
@TableName("sys_file_asset")
@Schema(name = "SysFileAsset", description = "文件资产")
public class SysFileAsset implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "文件md5")
    private String md5;

    @Schema(description = "文件大小")
    private Long fileLength;

    @Schema(description = "过期时间，过期文件将会被删除清理")
    private LocalDateTime expireTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String MD5 = "md5";

    public static final String FILE_LENGTH = "file_length";

    public static final String EXPIRE_TIME = "expire_time";

    public static final String CREATE_TIME = "create_time";
}
