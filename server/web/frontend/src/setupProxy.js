const {createProxyMiddleware} = require('http-proxy-middleware');

const target = "http://localhost:8081/";

module.exports = function (app) {
    app.use(
        '/team-talk-api',
        createProxyMiddleware({
            target: target,
            changeOrigin: true,
        })
    );
    app.use(
        '/team-talk-doc',
        createProxyMiddleware({
            target: target,
            changeOrigin: true,
        })
    );
};
