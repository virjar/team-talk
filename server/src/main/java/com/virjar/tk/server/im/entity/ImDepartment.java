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
 * 部门
 * </p>
 *
 * @author virjar
 * @since 2024-05-06
 */
@Getter
@Setter
@TableName("im_department")
@Schema(name = "ImDepartment", description = "部门")
public class ImDepartment implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自增主建")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "父级")
    private Long parentId;

    @Schema(description = "部门名称")
    private String name;

    @Schema(description = "部门主负责人")
    private String primaryAdmin;

    @Schema(description = "部门次负责人")
    private String secondAdmin;

    @Schema(description = "部门前缀")
    private String departmentPrefix;

    @Schema(description = "是否创建部门群聊（对于集团/公司级别的不能创建大群聊，对于小部门，可以自动创建工作群聊）")
    private Boolean createDepGroup;

    @Schema(description = "创建者")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String PARENT_ID = "parent_id";

    public static final String NAME = "name";

    public static final String PRIMARY_ADMIN = "primary_admin";

    public static final String SECOND_ADMIN = "second_admin";

    public static final String DEPARTMENT_PREFIX = "department_prefix";

    public static final String CREATE_DEP_GROUP = "create_dep_group";

    public static final String CREATED_BY = "created_by";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}
