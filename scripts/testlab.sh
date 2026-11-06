#!/bin/bash
# Firebase Test Lab Robo 测试：上传调试包 → 等结果 → 下载截图 / 日志到 .testlab/<时间>/
# 用法：./scripts/testlab.sh [apk]   默认 dist/xyzcar-<版本>-debug.apk
# 前置：gcloud auth login；gcloud config set project xyzcar-test
# 可选：TESTLAB_ORIENTATIONS="landscape portrait"（默认两种）、TESTLAB_ROBO_SCRIPT=脚本.json（先按脚本操作再自由探索）
set -euo pipefail
cd "$(dirname "$0")/.."

ver=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
apk="${1:-dist/xyzcar-$ver-debug.apk}"
[ -f "$apk" ] || { echo "找不到 $apk"; exit 1; }
model="${TESTLAB_MODEL:-MediumTablet.arm}"
api="${TESTLAB_API:-32}"
ts=$(date +%Y%m%d-%H%M%S)
out=".testlab/$ts"
mkdir -p "$out"

orients="${TESTLAB_ORIENTATIONS:-landscape portrait}"
args=()
for o in $orients; do args+=(--device "model=$model,version=$api,locale=zh_CN,orientation=$o"); done
[ -n "${TESTLAB_ROBO_SCRIPT:-}" ] && args+=(--robo-script "$TESTLAB_ROBO_SCRIPT")
echo "[testlab] 上传 ${apk}，设备 ${model} API ${api}（${orients}）"
# gcloud 自己等测试结束；外层再加 25 分钟上限
set +e
perl -e 'alarm shift; exec @ARGV' 1500 gcloud firebase test android run \
  --type robo --app "$apk" \
  "${args[@]}" \
  --timeout 5m --results-dir "xyzcar/$ts" --format=json \
  >"$out/result.json" 2>"$out/gcloud.log"
rc=$?
set -e
grep -E 'Test \[|matrices|outcome|FAILED|ERROR' "$out/gcloud.log" || true
cat "$out/result.json"

bucket=$(grep -oE 'storage/browser/[^/]+/xyzcar/'"$ts" "$out/gcloud.log" | head -1 | sed -E 's#storage/browser/##')
if [ -n "$bucket" ]; then
  echo "[testlab] 下载截图和日志 gs://$bucket"
  gcloud storage cp -r "gs://$bucket/*" "$out/" >/dev/null 2>&1 || echo "[testlab] 下载失败"
  find "$out" -name '*.png' | wc -l | xargs echo "[testlab] 截图数"
fi
echo "[testlab] 结果目录 ${out}，gcloud 退出码 $rc"
exit $rc
