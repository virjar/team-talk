# 首页二维码资源

`server/server/src/main/resources/static/js/qrcode.min.js` 是固定、自包含的浏览器资源，仅提供
`TeamTalkQr.encode(text)`，不包含 React，不向外部服务发送下载地址。
源码来自管理台已有依赖 `@rc-component/qrcode@1.1.3` 的 `es/libs/qrcodegen.js`，即 Project Nayuki
的 MIT 二维码算法；编译所需 Babel helpers 来自锁定的 `@babel/runtime@7.29.7`，打包器为
`esbuild@0.28.2`。来源与许可保留在相邻 `home-qrcode-LICENSE.txt` 及生成 JS 顶部。

在仓库根目录使用 Node.js 18+ 重建：

```bash
npm ci --prefix server/admin
node scripts/vendor/build-home-qrcode.mjs
```

依赖版本和完整性以 `server/admin/package-lock.json` 为准，不安装新依赖，不手改生成文件。
构建入口直接导入算法文件，避免把管理台 React 组件及应用打入首页。下载卡片使用当前通道的真实
APK 按钮地址生成带四模块留白的黑白 SVG；下载按钮和二维码指向同一绝对 URL。
