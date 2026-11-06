# 开发

## 构建

需要 JDK 17、Android SDK（platform 35、build-tools 35.0.0）。

```bash
./gradlew assembleDebug        # 调试包
./gradlew testDebugUnitTest    # 单元测试
./gradlew assembleRelease      # 正式包，需要 signing/ 目录
```

调试包有演示模式，不登录也能看到界面，适合截图：

```bash
adb shell am start -n com.ryan.xyzcar/.MainActivity --ez demo true
```

也可以在调试包登录页点「演示模式（调试包）」进入。调试包在 Firebase Test Lab 上会自动进演示模式，云端验证步骤见 `docs/cloud-test.md`。

## 验证真实登录（在电脑上，不需要车机）

`scripts/login-check.sh` 用和 App 完全相同的接口代码走一遍真实登录：显示二维码 → 手机小宇宙 App 扫码确认 → 拉订阅、星标、历史、进度 → 主动刷新 token → 用新 token 再拉订阅和发现页 → 逐个检查榜单、编辑精选、新节目广场、分类、搜索、单集 / 节目详情等页面接口（都是只读，不改账号数据）。某个接口返回 0 条会标 ⚠️，通常是小宇宙改了返回格式。

```bash
./scripts/login-check.sh          # 在你自己的终端里运行，用手机小宇宙 App 扫码并确认
./scripts/login-check.sh check    # 之后复查：用保存的登录态再跑一遍（不用再扫码）
./scripts/login-check.sh show     # 只读：列出发现页各区块、编辑精选、三个榜单的前几条，并检查音频能否访问
./scripts/login-check.sh dump     # 只读：导出公开内容快照，给 debug 演示模式和界面截图用
```

- `check` 每次运行会主动刷新一次 token（用来验证刷新链路），不要频繁跑。

- 二维码会打印在终端里（适合深色背景），同时保存为 `.logincheck/qr.png` 并自动打开；终端里的扫不出来就扫图片。
- 一个二维码 2 分钟内有效，过期自动换新的；整个过程最多等 4 分钟。
- 每次扫码状态变化都会打印「服务端返回：…」（HTTP 状态码、状态、带回了哪些头和 cookie 的名字），方便排查；不显示 token 值。
- token 保存在 `.logincheck/tokens.properties`（已 gitignore，权限 600）；不需要了直接删掉这个目录。
- 这一步验证的是接口链路（请求头、登录、刷新、数据接口），**不包括 App 界面上的登录流程**；界面上的登录需要把调试包装到任意安卓手机或车机上试。
- 第三方客户端登录可能触发风控，建议先用小号验证。

## 界面截图（不需要模拟器）

```bash
./gradlew recordPaparazziDebug   # 输出到 app/src/test/snapshots/images/
```

用公开内容快照和本地封面在电脑上渲染主要界面（横屏 / 分屏窄屏、浅色 / 深色、特大字号、全屏播放页），当前效果打开 `docs/screenshots/index.html` 查看。没有快照时自动用合成数据。

## 签名密钥（重要）

`signing/release.jks` 和 `signing/signing.properties` 是正式包的签名，已加入 `.gitignore`。**请自行备份**：之后升级安装必须用同一个密钥，丢了就只能卸载重装（本地播放队列和设置会丢，账号数据在服务端不受影响）。

## 发布

推送 `v<versionName>` 标签触发 `.github/workflows/release.yml`：校验标签与 `app/build.gradle.kts` 的 versionName 一致 → 单元测试 → 签名构建 → 创建 GitHub Release 并上传 `xyzcar-<版本>.apk`。

```bash
git tag v0.0.2 && git push origin v0.0.2
```

仓库 Secrets（一次性配置）：`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。

## 提交规范

见 `.claude/rules/git-commit.md`。clone 后执行一次 `git config core.hooksPath .githooks`。
