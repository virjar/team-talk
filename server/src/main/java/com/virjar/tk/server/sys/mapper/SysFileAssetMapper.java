package com.virjar.tk.server.sys.mapper;

import com.virjar.tk.server.sys.entity.SysFileAsset;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * <p>
 * 文件资产 Mapper 接口
 * </p>
 *
 * @author virjar
 * @since 2024-05-07
 */
public interface SysFileAssetMapper extends R2dbcRepository<SysFileAsset,Long> {

}
