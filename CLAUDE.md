# xyzcar

小宇宙 FM 非官方车机客户端。Kotlin + Jetpack Compose + Media3，个人非商用（PolyForm NC 1.0.0）。

- 需求：`docs/PRD.md`；进度：`task.md`；开发 / 发布：`docs/development.md`
- 接口：`app/src/main/java/com/ryan/xyzcar/data/api/XyzApi.kt`（参考 ultrazg/xyz，只参考路径和参数）
- 回复用中文

## 目标设备

- 海豹 08 EV，DiLink 150，OTA V1.1.1，优先适配
- 车机优先：大触控区、横屏侧边导航，窄屏（分屏、竖屏）切底部导航
- 视觉参考官方 iOS 日间风格，提炼而非照搬

## MUST

1. 所有切换（页面、Tab、筛选、面板、主题）带顺滑动画。
2. 能自己验证的先自己验证（只读接口探测、开源项目、网上检索），再找用户讨论。
3. `task.md` 只追加新记录，不改已有记录。
4. 车机实测前版本保持 `0.0.x`；versionName 与 versionCode 同步递增，发布只靠推 `v<versionName>` 标签。
5. 提交遵守 `.claude/rules/git-commit.md`。
6. 推送 GitHub 前检查私人信息。

## NEVER

1. 未经确认执行账号写操作：订阅 / 取消、星标、评论、改资料、删历史、退出登录、发消息。读与播放控制已授权。
2. 主动或定时刷新 token；只在 401 时刷新，间隔 ≥ 60 秒，不重放旧 refresh token。
3. 打印或保存 token、手机号、验证码；绕过人机验证。
4. 提交 `signing/`、`.logincheck/`、`local.properties`、`dist/`、`app/src/debug/assets/demo_snapshot.json`。
5. 本地跑模拟器；云端验证用 Firebase Test Lab，由用户上传。
6. 复制参考项目代码（BYDMate 等），只借鉴思路。
7. 未经要求 commit / push / 打 tag；替用户设置仓库 Secrets。

## 常用命令

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew recordPaparazziDebug       # 截图 → docs/screenshots/
./scripts/login-check.sh check       # 会刷新一次 token，勿频繁跑
```
