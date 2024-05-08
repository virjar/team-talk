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
 * 消息压缩归档，超过特定时间的历史消息，将会被压缩归档存储
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_msg_archive")
@Schema(name = "ImMsgArchive", description = "消息压缩归档，超过特定时间的历史消息，将会被压缩归档存储")
public class ImMsgArchive implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "时间窗口(一个小时一条记录)")
    private String timeKey;

    @Schema(description = "归档文件地址")
    private String ossPath;

    @Schema(description = "总条目数")
    private Integer totalCount;

    @Schema(description = "是否被删除（删除过程是逻辑假删，并且清空文件存储。db数据可以继续用来做统计）")
    private Boolean deleted;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String TIME_KEY = "time_key";

    public static final String OSS_PATH = "oss_path";

    public static final String TOTAL_COUNT = "total_count";

    public static final String DELETED = "deleted";

    public static final String CREATE_TIME = "create_time";
}
