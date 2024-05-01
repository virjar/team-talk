package com.virjar.tk.server.service.base.perm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * teamTalk的简单权限管理模型，只提供最简化的权限控制，即：作用域/权限
 * <br/>
 * 基于当代"逆中台化"的软件演化趋势，teamTalk主要用户小作坊的小平台，提供最简化的中间件能力，权限控制也仅仅提供支持最基本的控制。
 * 这是因为单一系统即面对B端少量用户，大部分情况下对权限使用是：功能监控大于功能约束（绝大部分B端系统在实际生产之后都是所有项目成员人均管理员），
 * 故复杂权限管理应该交由专门的大型中间件团队维护，以及本身应处理几百上千用户以上的多角色场景。
 * <br/>
 * 所以最简化可见性权限控制即为我们的合理能力边界
 *
 * <ul>
 *     <li>只控制数据可见性，不提供下沉的action，如更细粒度的读、写、操作等能力</li>
 *     <li>不提供权限组能力：即角色概念</li>
 *     <li>不支持层级权限：即普通用户的权限借用、grant等</li>
 *     <li>权限模型和数据库单一实体绑定，即主要用于控制数据库中某类数据可见权限</li>
 * </ul>
 *
 * @param <T> 权限模型绑定实体类型
 */
public abstract class Permission<T> {
    @Getter
    private final Class<T> clazz;

    public Permission(Class<T> tClass) {
        this.clazz = tClass;
    }

    /**
     * @return 本权限模型对应的作用域，作用域代表一个具体的权限模型，具备特定的权限处理规则
     */
    public abstract String scope();


    /**
     * 当前权限具备的枚举权限选项，如数据库中某些数据分组，它主要用于在前端展示，指导前端用户配置具体的权限规则
     *
     * @return 权限枚举
     */
    public Collection<String> perms() {
        return Collections.emptyList();
    }

    /**
     * 根据权限选项，设置过滤sql规则，如此在sql查询数据是
     *
     * @param perms 当前的权限枚举列表
     * @param sql   数据库条件组装对象，支持查询条件和修改条件
     */
    public void filter(Collection<String> perms, QueryWrapper<T> sql) {
    }

    /**
     * 根据权限选项，过滤数据实体，即在内存中过滤满足权限规则的数据
     *
     * @param perms 当前的权限枚举列表
     * @param input 被过滤的输入
     * @return 过滤后的返回列表
     */
    public List<T> filter(Collection<String> perms, List<T> input) {
        return input.stream()
                .filter(t -> hasPermission(perms, t))
                .collect(Collectors.toList());
    }

    /**
     * 直接判定特定实体是否具备权限
     *
     * @param perms 当前的权限枚举列表
     * @param t     判定实体
     * @return 是否具备权限
     */
    public boolean hasPermission(Collection<String> perms, T t) {
        return false;
    }
}
