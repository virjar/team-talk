import config from "../config";

const api_prefix = config.api_prefix;

export default {
    // system
    systemInfo: api_prefix + "/system/systemInfo", // 系统信息，后台系统配置，页面加载加载一次
    notice: api_prefix + "/system/systemNotice",//系统通告信息
    getIntPushMsg: "/yint-stub/certificate/getIntPushMsg", // 授权信息(仅限因体加密环境)
    getNowCertificate: "/yint-stub/certificate/getNowCertificate", // 授权证书(仅限因体加密环境)


    // admin
    userLogin: api_prefix + "/admin-op/travelToUser", // 模拟登录
    userAdd: api_prefix + "/admin-op/createUser", // 创建用户
    userList: api_prefix + "/admin-op/listUser", // 用户列表
    setConfig: api_prefix + "/admin-op/setConfig post", // config 单条
    setConfigs: api_prefix + "/admin-op/setConfigs post", // config all
    allConfig: api_prefix + "/admin-op/allConfig", // 所有 config
    settingTemplate: api_prefix + "/admin-op/settingTemplate",// 设置配置模版
    listServer: api_prefix + "/admin-op/listServer", // 列出 server
    setServerStatus: api_prefix + "/admin-op/setServerStatus", // 设置服务器状态
    grantAdmin: api_prefix + "/admin-op/grantAdmin", // 授权
    logList: api_prefix + "/admin-op/listSystemLog", // 日志


    // user
    login: api_prefix + "/user-info/login post query", // 登录
    register: api_prefix + "/user-info/register post query", // 注册
    getUser: api_prefix + "/user-info/userInfo", // 获取用户信息
    updatePassword: api_prefix + "/user-info/resetPassword post query", // 修改密码
    refreshToken: api_prefix + "/user-info/refreshToken", // 刷新 token
    regenerateAPIToken: api_prefix + "/user-info/regenerateAPIToken", // 刷新 api token
    permScopes: api_prefix + "/user-info/permScopes",
    permItemsOfScope: api_prefix + "/user-info/permItemsOfScope",
    editUserPerm: api_prefix + "/user-info/editUserPerm post query",


    // metric
    queryMetric: api_prefix + "/metric/queryMetric",// 查询指标
    metricNames: api_prefix + "/metric/metricNames",// 指标列表
    metricTag: api_prefix + "/metric/metricTag",// 指标tag分量
    deleteMetric: api_prefix + "/metric/deleteMetric",// 删除一个指标
    mqlQuery: api_prefix + "/metric/mqlQuery post query",// 使用mql查询指标数据，mql支持指标内容的加工
    allMetricConfig: api_prefix + "/metric/allMetricConfig",


    // 下列为各业务自定义接口
};
