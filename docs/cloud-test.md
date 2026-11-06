# 云端验证：Firebase Test Lab（免费）

用 Firebase Test Lab 的 Robo 测试在云端设备上自动点遍 App 的页面，产出截图、录屏和崩溃报告。不需要本地模拟器。

## 免费额度（Spark 套餐，不用绑卡）

- 虚拟设备：每天 10 次测试，合计不超过 60 分钟
- 真机：每天 5 次测试，合计不超过 30 分钟
- 每个「机型 × 系统版本 × 方向 × 语言」组合算一次测试

额度以 [Firebase 价格页](https://firebase.google.com/pricing) 为准。

## 用哪个包

上传最新的调试包 **`dist/xyzcar-0.0.1-debug.apk`**，不要传 release 包。

调试包在 Test Lab 设备上会自动进入演示模式（检测系统设置 `firebase.test.lab`）：

- 不需要登录
- 不请求小宇宙接口
- 不会让自动测试往登录框里乱填手机号、发验证码

release 包没有演示模式，自动测试只会停在登录页。

## 申请流程（一次性，约 5 分钟）

1. **准备**：一个 Google 账号；网络要能打开 `console.firebase.google.com`（国内需要代理）。
2. **创建项目**：打开 <https://console.firebase.google.com>，点「创建项目」。
   - 项目名随便填，例如 `xyzcar-test`
   - 同意条款
   - Google Analytics 选择**不启用**（用不到）
   - 点「创建项目」

   新项目默认就是免费的 Spark 套餐，不需要升级、不需要绑卡。
3. **不需要**在项目里「添加 Android 应用」，也不需要 `google-services.json`。Test Lab 可以直接测任意 APK。

## 每次验证的步骤

1. 在 Firebase 控制台左侧菜单进入 **Test Lab**（在「运行」或「DevOps 与互动」分组下）。
2. 点「运行测试」→「Robo」。
3. 上传最新调试包（如 `dist/xyzcar-0.0.1-debug.apk`）。
4. 设备选「自定义」，建议选两项（共占 2 次额度）：

   | 机型 | 系统 | 方向 | 语言 |
   |---|---|---|---|
   | 平板虚拟设备（如 Pixel Tablet） | Android 13 或 14 | 横向 | 中文（简体） |
   | 同上 | 同上 | 纵向 | 中文（简体） |

   DiLink 6.0 是 Android 12 左右的系统，列表里有 API 31/32 的平板虚拟设备时，优先选它。
5. 高级选项里把测试时长设为 5 分钟左右，省额度。
6. 点「开始测试」，等 5–10 分钟。

## 看结果

每台设备有一份结果，重点看这几样：

- **测试问题 / 崩溃**：有没有 `com.ryan.xyzcar` 的 crash 或 ANR。
- **屏幕截图**：确认横屏和竖屏下这些页面的布局，是否有文字截断或按钮被挤出屏幕：
  - 发现页、榜单、分类
  - 搜索
  - 历史
  - 播放队列
  - 单集详情
  - 设置
- **视频**：看 Robo 的点击路径。
- **Robo 爬取图**：看哪些页面没被点到。没覆盖到的页面，可以在控制台录一个 Robo 脚本补上。

调试包的演示模式内置一份真实公开内容快照（发现页、榜单、编辑精选、节目、单集），封面和音频地址都是真的，所以在 Test Lab 上能看到真实封面，点播放也能真的播放。订阅和历史列表是用公开节目编的，不含任何账号数据和 token。

## 命令行方式（推荐）

一次性准备：

```bash
brew install --cask gcloud-cli
gcloud auth login
gcloud config set project xyzcar-test
# macOS 会拦截未公证的 gcloud-crc32c，改用整文件下载 + MD5 校验
gcloud config set storage/use_gcloud_crc32c False
gcloud config set storage/sliced_object_download_threshold 0
```

每次运行：

```bash
./scripts/testlab.sh                 # 默认 dist/xyzcar-<版本>-debug.apk
./scripts/testlab.sh path/to.apk     # 指定包
```

- 设备：`MediumTablet.arm` API 32，中文，横 + 竖（占 2 次额度）；可用 `TESTLAB_MODEL` / `TESTLAB_API` 覆盖
- 结果下载到 `.testlab/<时间>/`（已 gitignore）：每台设备的截图、`logcat`、录屏、`sitemap.png`
