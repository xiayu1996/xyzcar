#!/bin/zsh
# 真实登录验证：在你自己的终端里运行（需要用手机扫码）。
#   ./scripts/login-check.sh          显示二维码 → 手机小宇宙 App 扫码确认 → 拉数据 → 刷新 token
#   ./scripts/login-check.sh check    用已保存的登录态再验证一次（不用扫码）
#   ./scripts/login-check.sh show     只读：列出为你推荐 / 编辑精选 / 三个榜单的前几条
#   ./scripts/login-check.sh dump     只读：导出公开内容快照（debug 演示模式和界面截图用）
#   可选：XYZ_APP_VERSION / XYZ_APP_BUILD 临时覆盖伪装的 App 版本号
# token 不会打印；token 保存在 .logincheck/（已 gitignore，权限 600）。
cd "$(dirname "$0")/.." || exit 1

run_step() {
  XYZ_STEP=$1 perl -e 'alarm shift; exec @ARGV' 300 ./gradlew --console=plain :app:testDebugUnitTest \
    --tests 'com.ryan.xyzcar.LoginCheck' 2>&1 \
    | grep --line-buffered -E '\[登录验证\]|Exception:|FAILED|BUILD (SUCCESSFUL|FAILED)' \
    | sed -u 's/^ *//'
  local rc=${pipestatus[1]}
  [ "$rc" = 142 ] && echo "[登录验证] ❌ 超过 5 分钟没有结束，已中止"
  return $rc
}

if [ "$1" = "check" ] || [ "$1" = "show" ] || [ "$1" = "dump" ]; then
  run_step "$1"; exit $?
fi

# 扫码登录：终端里会显示二维码，同时打开一张二维码图片；用手机小宇宙 App 扫码并确认
run_step qr
rc=$?
[ $rc = 0 ] && echo "[登录验证] 完成。之后可以用 ./scripts/login-check.sh check 复查（不用再扫码）"
exit $rc
