# dsh-mobile

DeepSeek Harness（DSH）的 Android 手机端工程，含两条路线：

| 目录 | 是什么 | 状态 |
|---|---|---|
| `android/` | **WebView 外壳**：把 DSH 官方 Web UI 装进原生壳（重前端快照、polyfill、移动外壳 CSS/JS 注入） | 可用（适配旧版 `dsh-remote-web-ui` 浏览器远程；该插件已被上游退役） |
| `native/` | **原生客户端**：走 `@linxin666/dsh-remote-bridge` 的协议 v1（`/ios/v1` WebSocket），原生渲染会话 | 进行中（P1 竖切：配对 + 列表 + 打开 + 发送） |
| `scripts/` | 调试与验证脚本：协议探针、CDP 抓包、前端快照抓取 | 可用 |

## 背景：为什么有两套

DSH 的远程访问在插件 `@linxin666/dsh-web-all` 0.4.13+ 发生换代：

- `dsh-remote-web-ui`（浏览器远程控制）**已退役**
- 替代者 `dsh-remote-bridge` 发布**协议 v1**，在宿主 `/ios/v1` 提供单个 WebSocket 面，专供原生 App 使用：
  配对（一次性 bootstrap token + P-256 挑战应答）、会话列表/快照/发言/中断、审批直达、指令、工作区、分支、用量、`ui_present` 卡片等

因此 `android/` 是历史路线（仍可用于回退），`native/` 是当前主线。

## 构建 android/（WebView 外壳）

```bash
cd android
# 1) 生成前端快照（从本机运行的 DSH host 抓取）
node ../scripts/snapshot-web.mjs
# 2) 构建
export JAVA_HOME=/path/to/jdk17
./gradlew assembleRelease
```

依赖：JDK 17、Android SDK（build-tools 35、platform 35）、Gradle 8.10+。

## 协议 v1 探针

```bash
node scripts/bridge-probe.mjs http://127.0.0.1:3080
```

会完整跑一遍：`GET /remote-bridge/pair/token` → `hello` → `pair.begin` → ES256 签名 → `pair.finish` → `session.list` → `session.snapshot`。

## 网络

手机通过 Tailscale 接入宿主：`wss://<host>.ts.net/ios/v1`（参考 `tailscale serve` 把 443 转发到 `127.0.0.1:3080`）。
本机调试可用 `adb reverse tcp:3080 tcp:3080` 后连 `ws://127.0.0.1:3080/ios/v1`。

## 许可

自用工程，未附许可证；涉及的第三方插件版权归其作者。
