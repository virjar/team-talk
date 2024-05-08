import uri from "./uri";
import axios from "axios";
import config from "config";

const prefix = "";

const LOGIN_USER_MOCK_KEY = config.login_user_key + "-MOCK";

let timer = null;

let reqs = {
    getStore: (isAdmin) => {
        const user = JSON.parse(localStorage.getItem(config.login_user_key) || "{}");
        if (isAdmin) {
            return user;
        }
        const userMock = JSON.parse(localStorage.getItem(LOGIN_USER_MOCK_KEY) || "{}");
        return userMock.mock ? userMock : user;
    },
    setStore: (user, key) => {
        const userMock = JSON.parse(localStorage.getItem(LOGIN_USER_MOCK_KEY) || "{}");
        key = key || (userMock.mock ? LOGIN_USER_MOCK_KEY : config.login_user_key);
        localStorage.setItem(key, JSON.stringify(user));
    }
};

function doRequest(request) {
    let user = reqs.getStore();
    return new Promise((resolve, reject) => {
        let newHeaders = request.headers ? request.headers : {};
        newHeaders = {...newHeaders}
        if (user) {
            newHeaders[config.login_token_key] = user['loginToken'];
        }
        axios({
            ...request,
            headers: newHeaders
        })
            .then((response) => {
                if (response.data.status === -1 && response.data.message.indexOf("请") >= 0
                    && response.data.message.indexOf("登录") > 0) {
                    localStorage.removeItem(config.login_user_key);
                    timer && clearTimeout(timer);
                    timer = setTimeout(() => {
                        window.location.href = "/#/sign-in";
                    }, 100);
                }
                resolve(response.data);
            })
            .catch((error) => {
                if (error.response) {
                    reject(error.response);
                } else {
                    reject(error)
                }
            });
    });
}

function doGet(uri, params = "", route = false) {
    if (route) {
        uri += params ? ("/" + params) : "";
    } else {
        // 组装参数
        let p = [];
        for (let i of Object.keys({...params})) {
            if (params[i] == null) {
                continue;
            }
            let key = encodeURIComponent(i);
            let value = encodeURIComponent(params[i]);
            p.push(`${key}=${value}`);
        }
        if (p.length > 0) {
            uri += "?" + p.join("&");
        }
    }
    return doRequest({
        method: "get",
        url: prefix + uri,
    })
}

function doPost(uri, data, query) {
    let postForm = function () {
        let p = [];
        for (let i of Object.keys({...data})) {
            if (data[i] != null) {
                let key = encodeURIComponent(i);
                let value = encodeURIComponent(data[i]);
                p.push(`${key}=${value}`);
            }
        }
        return p;
    }

    return doRequest({
        method: "post",
        url: prefix + uri,
        data: query ? postForm().join("&") : data,
    });
}

function doForm(uri, data) {
    let form = new FormData();
    for (let i of Object.keys({...data})) {
        if (data[i] != null) {
            form.append(i, data[i]);
        }
    }
    return doRequest({
        method: "post",
        url: prefix + uri,
        data: form,
    })
}

let urls = {}

for (let i of Object.keys(uri)) {
    const [url, method, query] = uri[i].split(" ");
    urls[i] = url;
    if (method === "post") {
        reqs[i] = (body) => doPost(url, body, query);
    } else if (method === "form") {
        reqs[i] = (body) => doForm(url, body);
    } else {
        reqs[i] = (params) => doGet(url, params, query);
    }
}

reqs["urls"] = urls;
export default reqs;
