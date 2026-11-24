#!/bin/zsh
# 真实登录验证，需在自己终端里用手机扫码：
#   ./scripts/login-check.sh          扫码登录 → 拉数据 → 刷新 token
#   ./scripts/login-check.sh check    用已保存的登录态复查
#   ./scripts/login-check.sh show     只读：列出推荐 / 精选 / 榜单前几条
#   ./scripts/login-check.sh dump     只读：导出公开内容快照
#   XYZ_PROBE=请求列表文件 ./scripts/login-check.sh probe   只读：原始返回存 .logincheck/probe/
#   可选 XYZ_APP_VERSION / XYZ_APP_BUILD 覆盖伪装的 App 版本
# 不打印 token；token 存 .logincheck/（gitignore，权限 600）
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

case "$1" in
  check|show|dump|probe) run_step "$1"; exit $? ;;
  "") ;;
  *) echo "[登录验证] ❌ 不认识的参数：$1（可用：check / show / dump / probe；不带参数是扫码登录）"; exit 2 ;;
esac

run_step qr
rc=$?
[ $rc = 0 ] && echo "[登录验证] 完成。之后可以用 ./scripts/login-check.sh check 复查（不用再扫码）"
exit $rc
