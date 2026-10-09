#!/usr/bin/env bash
# 用独立特权容器复用 rootfs 脚本，再通过 CNB 附件传递已校验的产物。
# Reuses the rootfs script in a privileged container and transfers checked outputs via CNB.
set -euo pipefail
: "${AZURPILOT_REF:?Missing upstream commit}" "${BUILD_KEY:?Missing build key}"
[[ "$AZURPILOT_REF" =~ ^[0-9a-f]{40}$ && "$BUILD_KEY" =~ ^[A-Za-z0-9_-]+$ ]]
case "${AZURPILOT_ABI:?Missing ABI}:$(uname -m)" in
    arm64-v8a:aarch64|x86_64:x86_64) ;;
    *) echo "Native runner does not match $AZURPILOT_ABI" >&2; exit 1 ;;
esac
container="azurpilot-rootfs-${BUILD_KEY}-${AZURPILOT_ABI}"
trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
docker build -f .cnb/Dockerfile.rootfs -t azurpilot-rootfs-builder .cnb
# docker cp 不依赖 DinD 宿主可见的路径，避免容器内 bind mount 指向空目录。
docker create --name "$container" --privileged \
    -e AZURPILOT_REF -e AZURPILOT_ABI -e AZURPILOT_REPO \
    azurpilot-rootfs-builder bash -c \
    'set -euo pipefail; python3 rootfs/tests/test_compact_runtime.py; python3 rootfs/tests/test_compact_ocr_models.py; bash rootfs/build/build-azurpilot.sh'
docker cp "$PWD/." "$container:/workspace/"
docker start --attach "$container"
[[ $(docker inspect --format '{{.State.ExitCode}}' "$container") == 0 ]]
mkdir -p .tmp/cnb-rootfs-output
docker cp "$container:/workspace/dist/." .tmp/cnb-rootfs-output/
(
    cd .tmp/cnb-rootfs-output
    sha256sum rootfs.tar.xz BUILD_MANIFEST frontend-*.tar.xz frontend-*.tar.xz.sha256 > SHA256SUMS
    tar -cf "../../cnb-rootfs-${BUILD_KEY}-${AZURPILOT_ABI}.tar" .
)
