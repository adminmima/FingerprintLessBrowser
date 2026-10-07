
FingerprintLessBrowser

基于 Tor Browser Android，去除 Tor 网络，保留反指纹保护 的浏览器实验项目。

---

项目状态

目标 状态
去除 Tor 网络代理 失败（尝试 10+ 种方案）
保留反指纹保护 未验证（因为无法上网）
绕过 Tor 引导页 成功（能进主页，有地址栏）
找到可行的技术路线 成功（GeckoView AAR 自组装）

详细技术总结见 PITFALLS.md。

---

核心结论

1. 反指纹和 Tor 网络可以分离，但直接改 APK 是死路
2. Tor Browser 的代理设置有至少 5 层，改 Java 层砍不到 C++ 层
3. 推荐方案：用 GeckoView AAR 从零组装浏览器，通过 GeckoRuntimeSettings 配置反指纹

---

推荐方案（GeckoView AAR 自组装）

```groovy
dependencies {
    implementation 'org.mozilla.geckoview:geckoview:139.0.20250603165925'
}
```

```java
GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
    .fingerprintingProtection(true)
    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
    .build();
GeckoRuntime runtime = GeckoRuntime.create(context, settings);
```

预估工作量：3-4 小时

---

保留的 Release

Tag 说明
cache-16.0a13 Tor Browser 16.0a13 原版 APK 缓存
v16.0a13-fp-navswap-* 成功绕过引导页的版本（能进主页，不能上网）

---

许可

仅用于学习研究，不得用于商业用途。

Tor Browser 是 Tor Project 的商标，本项目与之无关。修改后的 APK 仅供个人测试，不得再分发。
