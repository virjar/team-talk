package com.virjar.tk.server.service.base.dbcache;

import com.virjar.tk.server.entity.UserInfo;
import com.virjar.tk.server.mapper.UserInfoMapper;
import com.virjar.tk.server.service.base.BroadcastService;
import com.virjar.tk.server.service.base.dbcache.exs.UserEx;
import lombok.Getter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Service
public class DbCacheManager {


    @Resource
    private UserInfoMapper userInfoMapper;

    @Getter
    private DbCacheStorage<UserInfo, UserEx> userCacheWithName;
    @Getter
    private DbCacheStorage<UserInfo, Void> userCacheWithId;
    @Getter
    private DbCacheStorage<UserInfo, Void> userCacheWithApiToken;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    private void updateAllDbData() {
        BroadcastService.post(() -> {
            userCacheWithName.updateAll();
            userCacheWithApiToken.updateAll();
            userCacheWithId.updateAll();
        });
    }


    @PostConstruct
    public void init() {
        userCacheWithName = new DbCacheStorage<>(UserInfo.USER_NAME, userInfoMapper, updateHandlerUser);
        userCacheWithApiToken = new DbCacheStorage<>(UserInfo.API_TOKEN, userInfoMapper);
        userCacheWithId = new DbCacheStorage<>(UserInfo.ID, userInfoMapper);
        BroadcastService.register(BroadcastService.Topic.USER, () -> {
            userCacheWithName.updateAll();
            userCacheWithApiToken.updateAll();
            userCacheWithId.updateAll();
        });
    }

    private final DbCacheStorage.UpdateHandler<UserInfo, UserEx> updateHandlerUser = (userInfo, userEx) -> {
        if (userEx == null) {
            userEx = new UserEx();
        }
        userEx.reload(userInfo);
        return userEx;
    };
}
