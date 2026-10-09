# CNB 构建与国内下载 / CNB builds and domestic downloads

## 中文

### 下载入口

正式构建完成后，安装包发布到
[CNB Releases](https://cnb.cool/azurpilot/AzurPilot-for-Android/-/releases)。
首次安装选设备架构对应的完整包；已有运行环境时可用轻量包覆盖安装。
下载文件的 SHA-256 记录在同一发布的 `SHA256SUMS` 中。

| 文件 | 用途 |
|---|---|
| `AzurPilot-Android-<version>-arm64-v8a-full.apk` | ARM64 手机完整安装包 |
| `AzurPilot-Android-<version>-x86_64-full.apk` | x86_64 设备完整安装包 |
| `AzurPilot-Android-<version>-update.apk` | 双架构轻量更新包，不含运行时 |
| `rootfs-<abi>.tar.xz` | 对应架构的 Ubuntu / AzurPilot 运行时 |
| `BUILD_MANIFEST-<abi>.json` | 运行时来源和架构信息 |
| `frontend-<commit>.tar.xz` / `.sha256` | 预构建前端和校验和 |
| `latest.json` / `SHA256SUMS` | 下载清单及完整性校验 |

CNB 自行构建产物，下载附件由 CNB 提供。源码和构建依赖仍需要访问
GitHub、Google、Ubuntu、Termux 等上游。
App 内现有更新源仍使用 GitHub；CNB 下载入口适合手动安装完整包。
轻量包首次安装仍会经 App 原有更新源下载运行时。

### 触发与执行

仓库根目录 `.cnb.yml` 声明每日和手动构建入口：

- `main` 推送：只同步源码，不触发 CNB 构建，避免频繁提交消耗额度。
- `web_trigger_android`：CNB 主分支页面的「构建 Android 安装包」按钮，可指定
  AzurPilot dev 的完整提交 SHA，也可强制重建。
- `crontab: 17 3 * * *`：北京时间每天 03:17 固定构建一次双架构 Runtime 和 APK。
  定时入口通过 `cnb:apply` 调用 `api_trigger_android`，传入 `FORCE=true`。
  `git:release` 不支持直接用于 `crontab` 事件，因此完整构建与发布放在自定义事件中。

解析步骤将上游 dev 固定到具体提交，与上一次成功发布的 `latest.json` 比较。
比较范围为 `app/`、`rootfs/`、`.cnb.yml` 和 `.cnb/`。
手动构建默认跳过无变化的输入；每日构建不受此比较限制，固定重新构建。
应用版本规则与 GitHub CI 一致：无 Android 改动时沿用上次应用版本，
否则使用完整提交历史计算版本名，并保证版本号高于上一发布版。

`cnb:apply` 同步运行 `.cnb/rootfs.yml` 的两个原生子流水线：
ARM64 使用 `cnb:arch:arm64:v8`，x86_64 使用 `cnb:arch:amd64`。
各自通过 Docker 服务启动特权容器，提供原有 rootfs 脚本需要的 bind mount 和 chroot。
代码与产物用 `docker cp` 传递，不依赖 Docker 服务宿主上的工作区路径。

构建镜像通过 GitHub Releases 下载对应架构的 uv 二进制，避免本次 CNB 构建中
`ghcr.io` 令牌端点证书校验失败造成的镜像拉取错误。

中间附件以父流水线构建编号命名，保留 7 天。APK 流水线等待两架构均成功后，
下载本次附件，校验 SHA-256、ABI、上游提交和宿主提交，再构建三种 APK。
Gradle 使用 JDK 25 守护进程，Java / JVM 编译目标仍为 17。
镜像使用 Command-line Tools 23.0，安装 Compile SDK 37（官方包名
`platforms;android-37.0`）、Build Tools 36.0.0 和 CMake 3.22.1；NDK 由 AGP 安装。

### 配置正式凭据

GitHub Secrets 不会随代码同步到 CNB。首次启用正式发布前：

1. 在 CNB 建立私密仓库 `azurpilot/build-secrets`，创建 `android.yml`。
2. 复制 GitHub CI 当前使用的六个凭据；签名必须用同一 keystore，才能覆盖安装原来的正式包。
3. 在私密文件的授权配置中，只允许本项目读取。按 CNB
   [文件引用授权](https://docs.cnb.cool/zh/build/file-reference.html) 配置仓库访问权限。
4. 在 `.cnb.yml` 的 `imports` 中启用已预留的私密文件地址。
   若私密仓库或文件名不同，调整地址。
5. 在 CNB 主分支页面点击构建按钮并开启强制重建。

私密文件结构如下，真实值只能填写在私密仓库：

```yaml
AZURPILOT_ANDROID_KEYSTORE_BASE64: '<与 GitHub 相同的 keystore base64>'
AZURPILOT_ANDROID_KEYSTORE_PASSWORD: '<keystore 密码>'
AZURPILOT_ANDROID_KEY_ALIAS: '<签名 alias>'
AZURPILOT_ANDROID_KEY_PASSWORD: '<签名 key 密码>'
AZURPILOT_DEVICE_REPORT_CERT_BASE64: '<上报证书 base64>'
AZURPILOT_DEVICE_REPORT_KEY_BASE64: '<上报私钥 base64>'
```

本仓 `.cnb/env.yml` 只有空默认值。四个签名值全部为空时，每日和手动构建生成
带 `-debug.apk` 后缀的调试包，只作为提交附件保留 14 天。
调试产物生成 `build-info.json`，不会生成正式 `latest.json` 或创建 Release；
每日构建也遵循同样的正式签名规则，不会把调试包作为正式版本发布。
签名值不完整、上报证书和私钥缺一、正式签名缺少上报凭据时均提前失败。

上报证书在构建前检查有效期和公私钥匹配；APK 构建后验证 OCR、离线机型表及
凭据资产，并按 API 范围验证 v1 / v2 / v3 签名。
临时 keystore 和上报凭据在脚本退出及流水线结束时清理。

### 发布完成与维护

每次正式发布使用独立标签 `cnb-android-<build-id>`。
创建 Release、上传附件全部成功后才标记为 Latest，旧版本保留用于回滚。
`latest.json` 中的下载 URL 指向该版本的 CNB 附件，并保留 arm64 的旧版兼容字段。
维护者可按需要手动清理历史 Release。
完整构建使用 6 小时互斥锁，手动和每日构建按顺序执行。
每日调度入口不持有该锁，避免同步等待子流水线时形成死锁。

原有 GitHub 构建、上游检查和代码同步工作流继续保留。
CNB 发布标签不会由当前同步工作流修剪。
本地可执行以下验证；不能代替 CNB 上的首次双架构完整构建：

```powershell
uv run --no-project python .cnb/tests/test_artifacts.py
cd app
.\gradlew.bat :app:compileDebugKotlin -x verifyBundledAzurPilotRuntime
```

CNB 配置应按官方
[配置 Schema](https://docs.cnb.cool/conf-schema-en.json) 和
[手动按钮 Schema](https://docs.cnb.cool/web-trigger-schema-en.json) 校验。

## English

### Downloads

Successful signed builds publish APKs to
[CNB Releases](https://cnb.cool/azurpilot/AzurPilot-for-Android/-/releases).
Choose the full APK matching the device architecture for a first installation.
Use the slim APK to update a device with an existing runtime.
The release's `SHA256SUMS` records each download's SHA-256.

| File | Purpose |
|---|---|
| `AzurPilot-Android-<version>-arm64-v8a-full.apk` | Full installation for ARM64 phones |
| `AzurPilot-Android-<version>-x86_64-full.apk` | Full installation for x86_64 devices |
| `AzurPilot-Android-<version>-update.apk` | Universal slim update without a runtime |
| `rootfs-<abi>.tar.xz` | Ubuntu / AzurPilot runtime for that ABI |
| `BUILD_MANIFEST-<abi>.json` | Runtime provenance and architecture |
| `frontend-<commit>.tar.xz` / `.sha256` | Prebuilt frontend and checksum |
| `latest.json` / `SHA256SUMS` | Download manifest and integrity checks |

CNB builds the artifacts itself and serves their attachments. Source and build dependencies still
require upstream access to GitHub, Google, Ubuntu, Termux, and other providers.
The app's existing update source continues to use GitHub; CNB serves manual full-APK installations.
A first installation using the slim APK still downloads its runtime through the app's existing source.

### Triggers and execution

The repository's `.cnb.yml` provides daily and manual builds:

- `main` pushes only synchronize source and do not start CNB builds, conserving quota.
- `web_trigger_android`, exposed through the Android build button on the CNB main branch.
  It accepts a full AzurPilot dev commit SHA and a force-rebuild switch.
- `crontab: 17 3 * * *`, rebuilding both Runtime architectures and APKs once a day at 03:17
  Asia/Shanghai. The scheduler calls `api_trigger_android` through `cnb:apply` with `FORCE=true`.
  Since `git:release` does not support `crontab` directly, the custom event builds and publishes.

Resolution pins upstream dev to a commit and compares it against the last published `latest.json`.
Input comparisons cover `app/`, `rootfs/`, `.cnb.yml`, and `.cnb/`.
Manual builds skip unchanged inputs by default; daily builds always rebuild.
App versions follow GitHub CI: retain the previous app version when Android inputs are unchanged;
otherwise derive the name from full Git history and advance beyond the previous version code.

Synchronous `cnb:apply` runs both native child pipelines in `.cnb/rootfs.yml`:
`cnb:arch:arm64:v8` for ARM64 and `cnb:arch:amd64` for x86_64.
Each uses the Docker service to start a privileged container for the existing rootfs script's
bind mounts and chroot. Code and outputs move through `docker cp`, avoiding workspace-path
assumptions about the Docker service host.

Build images download the matching uv binary from GitHub Releases, avoiding the ghcr.io token
endpoint certificate-verification failure observed in this CNB build.

Intermediate attachments include the parent build ID and expire after seven days.
After both architectures succeed, the APK pipeline downloads this build's attachments and verifies
checksums, ABI, upstream commit, and host commit before building the three APKs.
Gradle runs on a JDK 25 daemon while Java / JVM compilation still targets 17.
The image uses Command-line Tools 23.0 and installs Compile SDK 37 (the official package name is
`platforms;android-37.0`), Build Tools 36.0.0, and CMake 3.22.1; AGP installs its NDK.

### Release credentials

GitHub Secrets are not mirrored with source code. Before enabling signed releases:

1. Create the private CNB repository `azurpilot/build-secrets` and its `android.yml` file.
2. Copy the six credentials used by GitHub CI. Use the same keystore to allow replacement
   installation over existing release APKs.
3. Authorize this project to read the private file according to CNB's
   [file-reference access rules](https://docs.cnb.cool/en/build/file-reference.html).
4. Enable the reserved private-file URL in `.cnb.yml` under `imports`.
   Adjust the URL if using another private repository or filename.
5. Trigger the build button on CNB's main branch with force rebuilding enabled.

The private file has this structure; enter real values only in the private repository:

```yaml
AZURPILOT_ANDROID_KEYSTORE_BASE64: '<same keystore base64 as GitHub>'
AZURPILOT_ANDROID_KEYSTORE_PASSWORD: '<keystore password>'
AZURPILOT_ANDROID_KEY_ALIAS: '<signing alias>'
AZURPILOT_ANDROID_KEY_PASSWORD: '<signing key password>'
AZURPILOT_DEVICE_REPORT_CERT_BASE64: '<reporting certificate base64>'
AZURPILOT_DEVICE_REPORT_KEY_BASE64: '<reporting private key base64>'
```

The checked-in `.cnb/env.yml` contains empty defaults. When all four signing values are empty,
daily and manual builds produce `-debug.apk` files kept as commit attachments for 14 days.
Debug outputs include `build-info.json`, never a release `latest.json` or a Release.
Daily builds follow the same signing rules and never publish debug APKs as release versions.
Partial signing inputs, unpaired reporting credentials, or missing reporting credentials for
signed builds fail before runtime construction.

Reporting credentials are checked for certificate expiry and matching public keys before building.
Finished APKs undergo OCR, offline device-catalog, and credential-asset verification, followed by
v1 / v2 / v3 signature checks per API range.
Temporary signing and reporting files are cleared on script exit and pipeline completion.

### Publication and maintenance

Each signed release gets a unique `cnb-android-<build-id>` tag.
It becomes Latest only after release creation and attachment uploads succeed. Earlier releases
remain available for rollback. Download URLs in `latest.json` point at that version's CNB
attachments and retain legacy arm64 fields. Maintainers can remove older releases when needed.
A six-hour lock serializes complete builds from daily and manual triggers. The scheduler itself
does not hold that lock, avoiding a deadlock while synchronously waiting for its child pipeline.

Existing GitHub build, upstream-check, and mirror workflows remain available.
The current mirror workflow does not prune CNB release tags.
Run these local checks, then validate the first complete dual-architecture build on CNB:

```powershell
uv run --no-project python .cnb/tests/test_artifacts.py
cd app
.\gradlew.bat :app:compileDebugKotlin -x verifyBundledAzurPilotRuntime
```

Validate CNB configurations against the official
[pipeline schema](https://docs.cnb.cool/conf-schema-en.json) and
[manual-button schema](https://docs.cnb.cool/web-trigger-schema-en.json).
