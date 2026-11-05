# 几何面板 iOS · TrollStore 安装包

下载本目录的 [dashboard-trollstore.ipa](dashboard-trollstore.ipa)，在文件 App 中分享给 TrollStore 安装。
要求 arm64 iPhone / iPad、iOS 15.4 或以上，且已安装可用的 TrollStore。
并非所有更高 iOS 版本都能安装 TrollStore，具体以 [TrollStore 官方说明](https://github.com/opa334/TrollStore) 为准。

这是 WKWebView 在线面板壳，打开 `https://lswlc33.github.io/zeeho-3d-models/`，首次启动需要联网。
网页随线上发布更新，壳版本为 1.0.0；不是内置源码提交的离线网页包。
登录和偏好保存在独立 App 网页空间，不与 Safari 或 Android 同步；卸载可能丢失本机资料。

复用网页的登录、前台查询、云端操作、骑行记录和设置。
Android 原生后台采样、自动任务、小组件、BLE 感应钥匙、Wi-Fi Direct 投屏、图片导出未移植。
不提供原生下载管理器，需要保存导出文件时使用 Safari 网页版。
不申请私有权限，不解除沙盒；IPA 为 ad-hoc 签名，由 TrollStore 安装时重新签名。

构建来源与 SHA-256 见 [version.json](version.json)；原始源码提交号见 [source-commit.txt](source-commit.txt)。
下载后可在此目录执行 `sha256sum -c SHA256SUMS`（macOS 使用 `shasum -a 256 -c SHA256SUMS`）。
构建已检查导航规则、arm64 Mach-O、代码签名、Info.plist、图标和 ZIP 完整性。
**尚未进行 iPhone / iPad 实机安装、登录及云端车控验收。**
