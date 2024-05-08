import React, {createContext, useEffect, useState} from "react";
import moment from 'moment';
import apis from 'apis';
import {useSnackbar} from "notistack";
import {Loading} from "../components";

export const AppContext = createContext({});

const Adapter = (props) => {
    const {enqueueSnackbar} = useSnackbar();
    const [user, setUser] = useState({});
    const [api, setApi] = useState({});
    const [notice, setNotice] = useState('');
    const [systemInfo, setSystemInfo] = useState({
        "buildInfo": {
            versionCode: 1,
            versionName: "1.0",
            buildTime: "2024-01-00_00:00:00",
            buildUser: "yint",
            gitId: "458baa545ae941239cb62fec359a911c44cbfa3a"
        },
        "env": {
            demoSite: true,
            debug: true
        }
    });
    // 在调用任何业务代码之前，确保了完成第一次的登录token刷新，避免到业务模块的时候，token刷新还未完成，产生鉴权失败问题
    const [firstLogin, setFirstLogin] = useState(false);

    useEffect(() => {
        let newApi = {};

        for (let i of Object.keys(apis)) {
            if (typeof apis[i] !== "function") {
                continue;
            }
            newApi[i] = function () {
                const args = [].slice.call(arguments)
                const that = this;
                return new Promise((resolve, reject) => {
                    apis[i].apply(that, args).then((res) => {
                        if (res.status !== 0) {
                            console.log("call api " + i + " error :" + res.message)
                            enqueueSnackbar(res.message.substring(0, 50), {
                                variant: "error",
                                anchorOrigin: {
                                    vertical: "top",
                                    horizontal: "center"
                                }
                            });
                        }
                        resolve(res);
                    }).catch(err => {
                        console.error(err);
                        reject(err);
                    })
                });
            }
        }
        newApi["urls"] = apis["urls"];
        newApi['getStore'] = apis['getStore'];
        newApi['setStore'] = apis['setStore'];
        newApi['errorToast'] = (msg) => {
            enqueueSnackbar(msg, {
                variant: "error",
                anchorOrigin: {
                    vertical: "top",
                    horizontal: "center"
                }
            });
        }
        newApi['successToast'] = (msg) => {
            enqueueSnackbar(msg, {
                variant: "success",
                anchorOrigin: {
                    vertical: "top",
                    horizontal: "center"
                }
            });
        }
        setApi(newApi)
    }, [enqueueSnackbar])

    useEffect(() => {
        let u = apis.getStore();
        setUser({
            ...u,
            time: moment(new Date()).format('YYYY-MM-DD HH:mm:ss')
        });
        const refreshUserInfo = () => {
            apis.notice().then(res => {
                if (res.status === 0) {
                    setNotice(res.data);
                }
            }).catch(() => {
            });
            return apis.getUser().then(res => {
                if (res.status === 0) {
                    u = {
                        ...apis.getStore(),
                        ...res.data,
                        time: moment(new Date()).format('YYYY-MM-DD HH:mm:ss')
                    };
                    apis.setStore(u);
                    setUser(u);
                }
            });
        }
        refreshUserInfo().then((res) => {
            setFirstLogin(true);
        });
        apis.systemInfo().then(res => {
            if (res.status === 0) {
                setSystemInfo(res.data)
            }
        });
        let timer = setInterval(() => {
            refreshUserInfo();
        }, 60 * 1000);
        return () => {
            timer && clearInterval(timer);
        }
    }, []);

    return (
        <AppContext.Provider
            value={{
                user,// 你可以在全局访问到用户信息
                api,// 挂载到全局的，支持react特性的一些API
                setUser,// 登录，注销，穿越等功能需要修改用户内容
                notice,// 给用户推送的消息
                systemInfo// 后端的服务器配置信息，目前主要包括后端构建数据，后端系统系统配置等，一般来说前端根据这些配置进行一些功能性质的开关选型
            }}
        >
            {firstLogin ? props.children : <Loading/>}
        </AppContext.Provider>
    );
};

export default Adapter;
