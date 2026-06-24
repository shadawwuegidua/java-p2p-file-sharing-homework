#!/bin/bash
# P2P 测试脚本 — 编译、运行测试

set -e
cd "$(dirname "$0")"

PERF=false
for arg in "$@"; do
    [[ "$arg" == "--perf" ]] && PERF=true
done

echo "=========================================="
echo "  P2P 文件共享系统 — 自动测试"
echo "=========================================="

# 编译源码
echo ""
echo "编译源码..."
mkdir -p out/production
javac -d out/production src/common/*.java src/tracker/*.java src/peer/*.java src/client/*.java
echo "编译完成"

# 运行测试
echo ""
if $PERF; then
    echo "运行全部测试（含性能测试，耗时较长）..."
    java -cp p2p-tests.jar:out/production TestRunner --perf 2>&1
else
    echo "运行功能测试（加 --perf 参数可启用性能测试）..."
    java -cp p2p-tests.jar:out/production TestRunner 2>&1
fi

exit $?
