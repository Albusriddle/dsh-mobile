# 深海女仆工坊 (com.albus.dsh)

DSH 的 Android 外壳：页面仍由桌面上的 DSH host 提供（同一 origin，`/api` 与配对 cookie 都正常），
但壳自带的**静态资源快照**会接管 `/assets/*` 与 `/plugins/*` 的请求——首屏不再从网络上拉 ~4.4 MB 前端代码。

## 目录

```
dsh-android/
├── scripts/snapshot-web.mjs     # 从运行中的 host 抓取静态资源 → app/src/main/assets/web
├── app/src/main/assets/web/     # 快照（asset-index.json + files/）
├── app/src/main/java/com/albus/dsh/MainActivity.java
└── keystore.properties          # 指向 F:/Android/keys/dsh.jks（自签名）
```

## 构建

```bash
export JAVA_HOME='C:\Users\Albus\.jdks\jbr-17.0.14'
cd D:/DSHtempWS/dsh-android
/c/Users/Albus/.gradle/wrapper/dists/gradle-8.10.2-all/ag7jqek4sgufkt9vqz6sy9079/gradle-8.10.2/bin/gradle.bat assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 刷新前端快照（host 升级后）

```bash
node scripts/snapshot-web.mjs          # 默认从 http://127.0.0.1:3080 抓
```

未命中的资源会自动回落到网络，所以**过期快照不会让 App 崩溃**，只是首屏会回退到联网加载。

## 安装 / 配对

1. 手机（Tailscale 已连接）打开 `https://desktop-2o8tfg3.tailfc9cb6.ts.net/apk/dsh-maid-v1.0.0.apk`
2. 允许「安装未知应用」→ 安装
3. 打开 App → 右下角「配」→ 粘贴桌面「远程访问」面板里的配对链接
4. 配对一次后 365 天内不用再配

## 已知限制

- 仍需要手机 Tailscale 常开（或在家用局域网入口）
- 桌面 host 必须在线：会话/模型/工具都在 host 上执行
- 自签名 release：升级必须保持同一 keystore
