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
 * 服务器节点，多台服务器组成代理集群
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
@Getter
@Setter
@TableName("sys_server_node")
@Schema(name = "SysServerNode", description = "服务器节点，多台服务器组成代理集群")
public class SysServerNode implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "服务器id，唯一标记服务器")
    private String serverId;

    @Schema(description = "最后心跳时间")
    private LocalDateTime lastActiveTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    private String ip;

    @Schema(description = "服务端口")
    private Integer port;

    @Schema(description = "服务器是否启用")
    private Boolean enable;

    @Schema(description = "本地IP")
    private String localIp;

    @Schema(description = "工作ip")
    private String outIp;

    public static final String ID = "id";

    public static final String SERVER_ID = "server_id";

    public static final String LAST_ACTIVE_TIME = "last_active_time";

    public static final String CREATE_TIME = "create_time";

    public static final String IP = "ip";

    public static final String PORT = "port";

    public static final String ENABLE = "enable";

    public static final String LOCAL_IP = "local_ip";

    public static final String OUT_IP = "out_ip";
}
