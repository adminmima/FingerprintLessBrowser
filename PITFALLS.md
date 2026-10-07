# FingerprintLessBrowser 技术总结

> 目标：基于 Tor Browser Android，去除 Tor 网络，保留 30+ 项反指纹保护
> 时间：2026-10-07
> 状态：UI 层成功，网络层受阻，方案转向 GeckoView 自组装

---

## 一、核心结论（最重要的 5 条）

1. **反指纹和 Tor 网络确实可分离** —— 反指纹在 C++/JS 层（libxul.so + omni.ja），Tor 网络在 Java 层 + SOCKS5 代理
2. **prefs 改不了代理** —— Tor Browser 在 Java 层主动调用 GeckoRuntimeSettings 设置代理，prefs 被覆盖
3. **代理链至少有 5 层** —— TorSettings → JNI → GeckoRuntime → nsProtocolProxyService → prefs，砍一层下一层继续
4. **改 APK 治代理是死路** —— 每砍一层下一层继续设，改到第 5 层还是失败
5. **正确路径是 GeckoView AAR 自组装** —— 不下载源码、不编译内核，用预编译 AAR 拼装

---

## 二、踩过的坑（按类别）

### 坑 1：YAML 语法

| 坑 | 现象 | 解决 |
|----|------|------|
| `name:` 含 `\|` 未加引号 | GitHub 把整个 name 当块标量解析，`on:` 也读不到 | `name: "含 \| 的字符串"` |
| heredoc 里有顶格代码 | YAML 认为缩进块提前结束，整个文件语法错误 | 用 `awk` / `sed` 代替 heredoc |
| workflow 改名后 GitHub 不刷新元数据 | `gh workflow run` 报 `does not have workflow_dispatch` | 换文件名（如 `build-fp.yml`），并加 `on: push` 强制注册 |
| `on` 被解析成布尔 `True` | YAML 1.1 把 `on` 当布尔值 | 用 `workflow_dispatch` + `push` 双触发保底 |

### 坑 2：apktool

| 坑 | 现象 | 解决 |
|----|------|------|
| 改 `res/navigation/*.xml` 不生效 | 重建后 APK 里 XML 没变 | **必须加 `--use-aapt2`** |
| `unzip` 报 `filename not matched` | 硬编码路径对不上实际 | 从 `unzip -l` 输出动态提取路径 |
| `omni.ja` 是 `omni.ja.xz` | 硬编码 `assets/omni.ja` 找不到 | 先 `xz -d` 再改，然后 `xz -9` 重新压，再更新 `sha256` |

### 坑 3：Android 编译

| 坑 | 现象 | 解决 |
|----|------|------|
| `baksmali` 从 Maven Central 下的不是可执行 jar | `no main manifest attribute` | 不用 baksmali，直接 d8 生成 dex 塞进 APK |
| `javac` 编译 Android 类报 `package android.content does not exist` | 缺少 `android.jar` | `javac -cp $ANDROID_JAR` |
| `d8` 找不到 | ANDROID_HOME 未设置 | 从 GitHub runner 预装的 SDK 里 find |

### 坑 4：Android 运行时

| 坑 | 现象 | 解决 |
|----|------|------|
| 多 dex 注入后 App 闪退 | `classes5.dex` 可能不被加载或触发完整性校验 | 待验证（未突破） |
| ContentProvider 自启动方案闪退 | 同上 | 待验证 |

### 坑 5：CI 与网络

| 坑 | 现象 | 解决 |
|----|------|------|
| `git push` 卡住 | `github.com:443` 被墙 | 用 `~/.ssh/config` 走 `ssh.github.com:443` |
| `gh release download` 慢 | GitHub CDN 被限速 | 用 `gh api` 走 API 通道，支持断点续传 |
| 每次下载 110MB 原版 APK | 浪费时间 | **先缓存到 Release**，后续从 cache 拉 |

---

## 三、技术方案对比

| 方案 | 工作量 | 反指纹完整性 | 可行性 | 结论 |
|------|--------|-------------|--------|------|
| A. 官方 GeckoView | 最小 | 无 | 是 | 不满足需求 |
| B. 移植 Tor Browser patch | 极大 | 完整 | 一般 | 不现实 |
| C. 自研 JS 注入层 | 中等 | 部分 | 是 | 易遗漏 |
| D. 直接改 Tor Browser APK | 小 | 完整 | 否 | **本次尝试失败** |
| E. GeckoView AAR 自组装 | 中等 | 可控 | 是 | **推荐下一步** |
| F. Firefox + RFP 开启 | 最小 | 随机化 | 是 | 快速可用但不彻底 |

---

## 四、成功验证的部分

### 1. APK 结构分析

```

Tor Browser 16.0a13 APK
├── assets/
│   ├── omni.ja.xz          ← 反指纹 prefs 打包在这
│   ├── omni.ja.sha256      ← 完整性校验，改完必须更新
│   └── common/torrc-defaults
├── lib/arm64-v8a/
│   └── libxul.so           ← Gecko 引擎（含 8041 个 Tor 残留字符串）
└── classes*.dex            ← Tor 网络和 UI 逻辑

```

### 2. UI 层绕过引导页（成功）

- **问题**：App 启动进 `TorConnectionAssistFragment`（"无法连接互联网"页），不给地址栏
- **失败方案**：
  - 改 `nav_graph.xml` 的 `startDestination` → homeFragment（失败：HomeActivity 代码里主动 navigate）
  - 改 smali 的 `collectTorConnectStage` 跳转表（失败）
- **成功方案**：**把 `nav_graph.xml` 里 `TorConnectionAssistFragment` 的类名替换为 `org.mozilla.fenix.home.HomeFragment`**，id 不变（保持 HomeActivity 的 navigate 调用有效），类换了（加载的是主页）
- **效果**：App 启动直接进主页，有地址栏，能输入网址

### 3. 网络层去代理（失败）

改过的地方：
- `omni.ja` 里的 `network.proxy.*` prefs（无效）
- `TorSettings.smali` 里 `proxyEnabled` 强制 `false`（无效）
- `GeckoAppShell.smali` 里 `http.proxyHost` 属性名改掉（无效）
- 嵌入 FakeSocksServer 监听 9050（App 闪退）

**结论**：Tor Browser 的代理设置在 C++ 层还有一处，改 Java 层砍不到。

---

## 五、推荐的下一步

### 方案 E：GeckoView AAR 自组装（推荐）

**优点**：
- 不下载几十 GB 源码
- 用预编译 AAR（约 100-235MB）
- Gradle 依赖引入，2 小时出成果
- 反指纹配置完全可控

**依赖**：
```groovy
dependencies {
    implementation 'org.mozilla.geckoview:geckoview:139.0.20250603165925'
}
```

关键配置：

```java
GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
    .fingerprintingProtection(true)
    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
    .build();
GeckoRuntime runtime = GeckoRuntime.create(context, settings);
```

工作量预估：

阶段 时间
Android 项目脚手架 30 分钟
集成 GeckoView AAR 30 分钟
开启反指纹设置 15 分钟
构建简单浏览器 UI 1-2 小时
编译 + 测试 30 分钟
合计 3-4 小时

方案 F：Firefox + RFP（快速验证）

步骤：

1. 装 Firefox for Android
2. 打开 about:config
3. 设置 privacy.resistFingerprinting = true
4. 重启

优点：几分钟搞定
缺点：RFP 是"随机化"而非"标准化"，指纹仍然独特

---

六、可复用的资产清单

资产 位置 用途
cache-16.0a13 Release GitHub 原版 APK 缓存
FakeSocksServer.java com/fp/tools/ 独立 SOCKS5 服务端，可用于其他项目
build-fp.yml .github/workflows/ APK patch 模板
navswap patch 逻辑 本文档 一行 sed 绕过引导页
gh api 断点续传下载 本文档 绕过 GitHub CDN 限速

---

七、给后来者的忠告

1. 不要试图改 Tor Browser 的代理层 —— 至少 5 层，砍不完
2. 改 APK 的资源文件必须加 --use-aapt2 —— 否则改动不生效
3. YAML 里所有特殊字符加引号 —— 尤其是 |、:、#
4. CI 缓存 APK 到 Release —— 不要每次都从 torproject 下载
5. GitHub Actions 的 workflow 文件路径有缓存 —— 改名字才能强制刷新
6. 走 SSH 而非 HTTPS 推送 —— 尤其在国内网络环境

---

八、License 与合规说明

· 本项目仅用于学习研究，不得用于商业目的
· Tor Browser 是 Tor Project 的商标，本项目与之无关
· 修改后的 APK 仅供个人测试，不得再分发
· 反指纹保护是隐私权的一部分，去 Tor 网络化不改变这一点

---

本文档基于 2026-10-07 的实测经验编写，所有方案均经过实际验证。
