package com.virjar.tk.server.im.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.im.entity.ImUserInfo;
import com.virjar.tk.server.im.mapper.ImUserInfoMapper;
import com.virjar.tk.server.sys.AppContext;
import com.virjar.tk.server.sys.service.config.Settings;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.utils.Md5Utils;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.UUID;

/**
 * <p>
 * 用户信息 服务实现类
 * </p>
 *
 * @author iinti
 * @since 2022-02-22
 */
@Service
public class UserInfoService {

    @Resource
    private ImUserInfoMapper userMapper;
    private static final String salt = Constants.appName + "2024V2!@&*(";

    private final Cache<String, ImUserInfo> apiTokenCache = CacheBuilder.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();
    private final Cache<Long, ImUserInfo> userIdCache = CacheBuilder.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

    public CommonRes<ImUserInfo> login(String account, String password) {
        ImUserInfo userInfo = userMapper
                .selectOne(new QueryWrapper<ImUserInfo>().eq(ImUserInfo.USER_NAME, account).last("limit 1"));
        if (userInfo == null) {
            return CommonRes.failed("请检查用户名或密码");
        }
        if (!StringUtils.equals(userInfo.getPassword(), password)) {
            return CommonRes.failed("请检查用户名或密码");
        }

        userInfo.setWebLoginToken(genLoginToken(userInfo, LocalDateTime.now()));
        userMapper.update(null, new UpdateWrapper<ImUserInfo>()
                .eq(ImUserInfo.USER_NAME, userInfo.getUserName())
                .set(ImUserInfo.WEB_LOGIN_TOKEN, userInfo.getWebLoginToken()));
        return CommonRes.success(userInfo);
    }

    public CommonRes<ImUserInfo> resetUserPassword(Long userId, String password) {
        if (StringUtils.isBlank(password)) {
            return CommonRes.failed("密码格式不正确。");
        }
        ImUserInfo userInfo = userMapper
                .selectOne(new QueryWrapper<ImUserInfo>().eq(ImUserInfo.ID, userId).last("limit 1"));
        if (userInfo == null) {
            return CommonRes.failed("用户不存在");
        }
        userInfo.setPassword(password);
        userMapper.update(null, new UpdateWrapper<ImUserInfo>()
                .eq(ImUserInfo.USER_NAME, userInfo.getUserName())
                .set(ImUserInfo.PASSWORD, password));
        resetCache(userInfo);
        return CommonRes.success(userInfo);
    }

    public String refreshToken(String oldToken) {
        ImUserInfo userInfo = getUserInfoFromToken(oldToken);
        if (userInfo == null) {
            //token不合法
            return null;
        }
        if (isRightToken(oldToken, userInfo)) {
            return genLoginToken(userInfo, LocalDateTime.now());
        }
        return null;
    }

    private boolean isRightToken(String token, ImUserInfo userInfo) {
        for (int i = 0; i < 3; i++) {
            // 每个token三分钟有效期，算法检测历史9分钟内的token，超过9分钟没有执行刷新操作，token失效
            String historyToken = genLoginToken(userInfo, LocalDateTime.now().minusMinutes(i * 3));
            if (historyToken.equals(token)) {
                return true;
            }
        }
        return false;
    }

    public String genLoginToken(ImUserInfo userInfo, LocalDateTime date) {
        byte[] bytes = md5(userInfo.getUserName() + "|" + userInfo.getPassword() + "|" + salt + "|" + (
                date.get(ChronoField.MINUTE_OF_DAY) / 30) + "|" + date.getDayOfYear());
        //
        byte[] userIdData = longToByte(userInfo.getId());
        byte[] finalData = new byte[bytes.length + userIdData.length];

        for (int i = 0; i < 8; i++) {
            finalData[i * 2] = userIdData[i];
            finalData[i * 2 + 1] = bytes[i];
        }

        if (bytes.length - 8 >= 0) {
            System.arraycopy(bytes, 8, finalData, 16, bytes.length - 8);
        }

        return Md5Utils.toHexString(finalData);
    }

    public static long byteToLong(byte[] b) {
        long s;
        long s0 = b[0] & 0xff;// 最低位
        long s1 = b[1] & 0xff;
        long s2 = b[2] & 0xff;
        long s3 = b[3] & 0xff;
        long s4 = b[4] & 0xff;// 最低位
        long s5 = b[5] & 0xff;
        long s6 = b[6] & 0xff;
        long s7 = b[7] & 0xff;

        // s0不变
        s1 <<= 8;
        s2 <<= 16;
        s3 <<= 24;
        s4 <<= 8 * 4;
        s5 <<= 8 * 5;
        s6 <<= 8 * 6;
        s7 <<= 8 * 7;
        s = s0 | s1 | s2 | s3 | s4 | s5 | s6 | s7;
        return s;
    }

    public static byte[] longToByte(long number) {
        long temp = number;
        byte[] b = new byte[8];
        for (int i = 0; i < b.length; i++) {
            b[i] = Long.valueOf(temp & 0xff).byteValue();
            // 将最低位保存在最低位
            temp = temp >> 8;
            // 向右移8位
        }
        return b;
    }

    public static byte[] md5(String inputString) {
        try {
            byte[] buffer = inputString.getBytes(StandardCharsets.UTF_8);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(buffer, 0, buffer.length);
            return md5.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // 用户名中非法的字符,这是因为我们的系统将会直接使用用户名称做业务，一些奇怪的字符串将会引起一些紊乱
    // 如鉴权表达式：传递控制信息
    //   资产文件：使用用户名隔离多个用户的文件存储
    private static final char[] illegalUserNameChs = " -/\t\n*\\".toCharArray();
    private boolean hasAdminUser = false;

    public CommonRes<ImUserInfo> register(String account, String password) {
        if (StringUtils.isAnyBlank(account, password)) {
            return CommonRes.failed("用户或者密码不能为空");
        }
        if (StringUtils.containsAny(account, illegalUserNameChs)) {
            return CommonRes.failed("userName contain illegal character");
        }
        ImUserInfo userInfo = userMapper
                .selectOne(new QueryWrapper<ImUserInfo>().eq(ImUserInfo.USER_NAME, account).last("limit 1"));

        if (userInfo != null) {
            return CommonRes.failed("用户已存在");
        }

        if (!hasAdminUser) {
            hasAdminUser = userMapper.selectCount(new QueryWrapper<ImUserInfo>().eq(ImUserInfo.SYS_ADMIN, true)) > 0;
        }

        boolean isCallFromAdmin = AppContext.getUser() != null && AppContext.getUser().getSysAdmin();
        if (!isCallFromAdmin && hasAdminUser && !Settings.allowRegisterUser.value) {
            return CommonRes.failed("当前系统不允许注册新用户，详情请联系管理员");
        }

        userInfo = new ImUserInfo();
        userInfo.setUserName(account);
        userInfo.setPassword(password);
        userInfo.setApiToken(UUID.randomUUID().toString());
        // 第一个注册用户，认为是管理员
        userInfo.setSysAdmin(!hasAdminUser);

        int result = userMapper.insert(userInfo);
        if (result == 1) {
            userInfo.setWebLoginToken(genLoginToken(userInfo, LocalDateTime.now()));
            userMapper.update(null, new UpdateWrapper<ImUserInfo>()
                    .eq(ImUserInfo.USER_NAME, userInfo.getUserName())
                    .set(ImUserInfo.WEB_LOGIN_TOKEN, userInfo.getWebLoginToken()));
            return CommonRes.success(userInfo);
        }

        return CommonRes.failed("注册失败，请稍后再试");
    }


    public CommonRes<ImUserInfo> checkAPIToken(List<String> tokenList) {
        for (String token : tokenList) {
            ImUserInfo userInfo = apiTokenCache.getIfPresent(token);
            if (userInfo != null) {
                return CommonRes.success(userInfo);
            }
        }

        QueryWrapper<ImUserInfo> queryWrapper = new QueryWrapper<>();
        if (tokenList.size() == 1) {
            queryWrapper.eq(ImUserInfo.API_TOKEN, tokenList.get(0));
        } else {
            queryWrapper.in(ImUserInfo.API_TOKEN, tokenList);
        }

        ImUserInfo userInfo = userMapper.selectOne(queryWrapper.last("limit 1"));
        if (userInfo != null) {
            apiTokenCache.put(userInfo.getApiToken(), userInfo);
            return CommonRes.success(userInfo);
        }
        return CommonRes.failed("请登录");
    }

    public CommonRes<ImUserInfo> checkLogin(List<String> tokenList) {
        for (String candidateToken : tokenList) {
            CommonRes<ImUserInfo> res = checkLogin(candidateToken);
            if (res.isOk()) {
                return res;
            }
        }
        return CommonRes.failed("请登录");
    }

    public CommonRes<ImUserInfo> checkLogin(String token) {
        ImUserInfo userInfo = getUserInfoFromToken(token);
        if (userInfo == null) {
            return CommonRes.failed(CommonRes.statusNeedLogin, "token错误");
        }
        if (!isRightToken(token, userInfo)) {
            return CommonRes.failed("请登录");
        }
        userInfo.setWebLoginToken(genLoginToken(userInfo, LocalDateTime.now()));
        userMapper.update(null, new UpdateWrapper<ImUserInfo>()
                .eq(ImUserInfo.USER_NAME, userInfo.getUserName())
                .set(ImUserInfo.WEB_LOGIN_TOKEN, userInfo.getWebLoginToken())
        );
        //resetCache(userInfo);
        return CommonRes.success(userInfo);
    }

    @SneakyThrows
    private ImUserInfo getUserInfoFromToken(String token) {
        // check token format
        if (StringUtils.isBlank(token)) {
            return null;
        }
        if ((token.length() & 0x01) != 0) {
            //token长度必须是偶数
            return null;
        }
        if (token.length() < 16) {
            return null;
        }
        for (char ch : token.toCharArray()) {
            // [0-9] [a-f]
            if (ch >= '0' && ch <= '9') {
                continue;
            }
            if (ch >= 'a' && ch <= 'f') {
                continue;
            }
            return null;
        }

        byte[] bytes = Md5Utils.hexToByteArray(token);
        byte[] longByteArray = new byte[8];
        // byte[] md5BeginByteArray = new byte[8];
        for (int i = 0; i < 8; i++) {
            longByteArray[i] = bytes[i * 2];
            //  md5BeginByteArray[i] = bytes[i * 2 + 1];
        }
        long userId = byteToLong(longByteArray);
        return userIdCache.get(userId, () -> userMapper.selectById(userId));
    }

    public CommonRes<String> grantAdmin(String userName, boolean isAdmin) {
        ImUserInfo userInfo = userMapper.selectOne(new QueryWrapper<ImUserInfo>().eq(ImUserInfo.USER_NAME, userName));
        if (userInfo == null) {
            return CommonRes.failed("user not exist");
        }
        if (userInfo.getId().equals(AppContext.getUser().getId())) {
            return CommonRes.failed("you can not operate yourself");
        }
        userInfo.setSysAdmin(isAdmin);
        userMapper.update(null, new UpdateWrapper<ImUserInfo>().eq(ImUserInfo.USER_NAME, userInfo.getUserName())
                .set(ImUserInfo.SYS_ADMIN, isAdmin));
        resetCache(userInfo);
        return CommonRes.success("ok");
    }

    private void resetCache(ImUserInfo userInfo) {
        userIdCache.invalidate(userInfo.getId());
        apiTokenCache.invalidate(userInfo.getApiToken());
    }
}
