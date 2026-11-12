# dsh-mobile

**Android client for DeepSeek Harness (DSH)** — a native protocol-v1 WebSocket client plus a WebView shell for the official DSH web UI. Built for phones that reach their DSH host over [Tailscale](https://tailscale.com).

`deepseek-harness` · `deepseek` · `ai-agent` · `android` · `mobile-client` · `protocol-v1` · `websocket` · `tailscale` · `remote-control` · `webview`

---

## What is this?

DSH (DeepSeek Harness) is an agentic coding/automation host that runs on your desktop. This repo makes it usable from an Android phone:

| Directory | What it is | Status |
|---|---|---|
| `native/` | **Native client** speaking the `@linxin666/dsh-remote-bridge` **protocol v1** (`/ios/v1` WebSocket): pairing, session list, snapshot rendering, prompt/interrupt | Main line — P1 vertical slice working on-device |
| `android/` | **WebView shell**: ships a front-end snapshot inside the APK, polyfills old WebView kernels, injects a mobile layout | Legacy (targets the retired `dsh-remote-web-ui` browser remote; kept for rollback) |
| `scripts/` | Protocol probe (pairing + list + snapshot), CDP helpers, front-end snapshotter, GitHub-API pusher | Working |

### Why two apps

The DSH remote-access plugin changed in `@linxin666/dsh-web-all` 0.4.13+:

- `dsh-remote-web-ui` (browser-based remote control) was **retired upstream**
- Its replacement `dsh-remote-bridge` publishes **protocol v1**: one WebSocket face at `/ios/v1` designed for native apps — one-shot bootstrap token + P-256 challenge/response pairing, session list/snapshot/prompt/interrupt, approvals, commands, workspaces, fork, usage and `ui_present` cards

So `android/` is history and `native/` is the current line.

### Protocol v1 at a glance

```
GET  /remote-bridge/pair/token          (loopback only) → one-shot bootstrap token, 30 s TTL
WS   /ios/v1?token=<bootstrap>          → hello → pair.begin → ES256 sign nonce → pair.finish
                                          → deviceToken; later connects send
                                          Authorization: Bearer <deviceToken>
     session.list / session.snapshot / session.prompt / session.interrupt / ...
```

Try it without the app:

```bash
node scripts/bridge-probe.mjs http://127.0.0.1:3080
```

## Build

**native/** (protocol v1 client, Java + OkHttp)

```bash
cd native
export JAVA_HOME=/path/to/jdk17     # JDK 17, Android SDK 35, Gradle 8.10+
./gradlew assembleDebug
# test: adb reverse tcp:3080 tcp:3080, then open the app and pair with ws://127.0.0.1:3080
```

**android/** (WebView shell)

```bash
cd android
node ../scripts/snapshot-web.mjs    # snapshot the running DSH host's static assets
export JAVA_HOME=/path/to/jdk17
./gradlew assembleRelease
```

## Networking

Phones join the host's tailnet and use `wss://<host>.ts.net/ios/v1` (a `tailscale serve` rule forwards 443 → `127.0.0.1:3080`). For local debugging use `adb reverse tcp:3080 tcp:3080` and connect to `ws://127.0.0.1:3080`.

> Note: on a network where `github.com:443` is unreachable, pushes go through the GitHub REST API — see `scripts/github-push.mjs`.

---

# 中文说明

DeepSeek Harness（DSH）的 Android 手机端工程，含两条路线：

| 目录 | 是什么 | 状态 |
|---|---|---|
| `native/` | **原生客户端**：走 `@linxin666/dsh-remote-bridge` 协议 v1（`/ios/v1` WebSocket），原生渲染会话 | 主线，P1 竖切已在真机跑通 |
| `android/` | **WebView 外壳**：把官方 Web UI 装进原生壳（前端快照、polyfill、移动外壳注入） | 历史路线（适配已退役的 `dsh-remote-web-ui`，保留可回退） |
| `scripts/` | 协议探针、CDP 抓包、前端快照抓取、GitHub API 推送 | 可用 |

### native/ 进展（P1 竖切）

- ✅ 配对：bootstrap token + ES256(P-256) 挑战应答，`deviceToken` 持久化
- ✅ 重连：`Authorization: Bearer <deviceToken>`
- ✅ 会话列表：`session.list`，按 `cwd` 分组
- ✅ 打开会话：`session.snapshot` 渲染 user / assistant / tool 条目
- ✅ 发送与中断：`session.prompt` / `session.interrupt`，发送后限时轮询快照保证回复可见
- ✅ 实时增量：`session.delta` 去抖触发快照刷新
- ✅ 断线重连 + 状态条
- ✅ 会话搜索、重命名 / 置顶 / 归档（`session.admin.v1`）
- ✅ 扫码配对（ZXing）、前台服务保活、令牌 Keystore 加密
- ⚠️ 审批弹层（`approval.request` / `approval.resolve` / `approvals.v1` 重放）：**代码完成，本机未能触发验证**——探测时 agent 未真正调用需要授权的工具（见 `scripts/approve-probe*.mjs`）

### 网络

手机加入宿主的 tailnet，连 `wss://<host>.ts.net/ios/v1`（`tailscale serve` 把 443 转发到 `127.0.0.1:3080`）。本机调试：`adb reverse tcp:3080 tcp:3080` 后连 `ws://127.0.0.1:3080`。

## 许可

自用工程，未附许可证；涉及的第三方插件版权归其作者。
