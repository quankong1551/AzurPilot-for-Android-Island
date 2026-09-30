# 运行时热更新 / Runtime Hot Update

## 中文

热更新让已部署的 rootfs 在**不重装整包**的前提下，把 `/opt/azurpilot` 内的
AzurPilot 源码与 Web 前端资源增量更新到上游最新提交。整包 Runtime 更新
（见 [runtime-provisioning.md](runtime-provisioning.md)）只在基础镜像变更时才需要。

### 职责划分

| 环节 | 归属 | 说明 |
|---|---|---|
| 前端 dist 构建 | 本仓库 CI | `rootfs.yml` 构建期用 npm 构建并发布 `frontend-<commit>.tar.xz` + `.sha256` 到 Release |
| 预构建 dist 消费 | 上游 AzurPilot | `deploy/frontend.py` 安卓分支：指纹不匹配时从宿主渠道拉取 dist，**永不本地 npm 编译** |
| 更新触发与编排 | App | `AzurPilotRunController` 经 `/android/update/*` 私有接口轮询状态、触发应用；空闲时自动应用 |
| 源码获取 | 上游 AzurPilot | `module/api/android_update.py` 在运行时内浅 git 拉取（rootfs 出厂无 `.git`，首次自动初始化） |
| 实例恢复 | 上游 AzurPilot | 复用内置更新器的文件协议：`config/reloadalas`（实例名单）+ `config/webui-dependency-sync-pending`（依赖同步标记），父监督器重启 WebUI 后由 `lifecycle.startup()` 自动拉起实例 |

### 安全顺序（防“源码与 dist 失配启动崩溃”）

1. 应用前探测：上游头的 `frontend-<sha>.tar.xz.sha256` 不存在（CI 未编译完 /
   断网）→ 视为**暂不可更新**，保持旧版本；
2. 写恢复计划（reloadalas + 依赖同步标记）→ 浅 `git fetch --depth 1` + `reset --hard`；
3. 下载并校验 dist（sha256 + 源码指纹双校验）；失败则 `git reset` 回滚旧提交；
4. 改写 `BUILD_MANIFEST`（`azurpilot_commit` 等；`rootfs_version` 不动——区分规则见下节）；
5. 触发 `restart_event`：父监督器完成依赖同步后重启 WebUI，实例按 reloadalas 恢复。

### 整包更新与热更的区分

`rootfs_version` 是复合串：`<上游提交前12位>-<rootfs 构建输入哈希前10位>`（输入 =
build 脚本、overlays、seeds 等宿主侧内容）。热更只改写 `azurpilot_commit` 等字段，
`rootfs_version` 保持原样。App 的整包更新判据因此拆成两层：

- **构建输入哈希（末段）一致**：基础镜像无差异，整串不等仅因源码前进 →
  置 `RuntimeUpdateCheck.commitOnly`，不弹整包更新、不阻塞 proot 启动，
  差异交给热更通道；
- **构建输入哈希变化**：Python 版本、系统库、overlay/种子内容变了 →
  必须整包重部署，照常弹窗。

逃生门：关闭设置里的热更开关，整包更新提示立即恢复（用于热更通道不可用时强制走整包）。

### App 侧约定

- 环境变量 `AZURPILOT_ANDROID_DIST_BASE`：镜像前缀 + Release 基址，由 `ProotHost`
  按用户的下载源设置拼装注入，变更后下次会话生效；
- 设置开关 `hotUpdateEnabled`（默认开）；手动入口在 设置 → Runtime；
- 自动应用条件：开关开、有可用更新、调度器与工具均空闲。

---

## English

The hot update incrementally brings the AzurPilot source and web frontend under
`/opt/azurpilot` up to the latest upstream commit **without reinstalling the
rootfs**. The full-runtime update (see
[runtime-provisioning.md](runtime-provisioning.md)) is only needed when the base
image changes.

### Ownership

| Concern | Owner | Notes |
|---|---|---|
| Frontend dist build | This repo's CI | `rootfs.yml` builds it with npm and publishes `frontend-<commit>.tar.xz` + `.sha256` to the Release |
| Prebuilt dist consumption | Upstream AzurPilot | `deploy/frontend.py`'s Android branch fetches the dist from the host channel on fingerprint mismatch — **never compiles locally with npm** |
| Trigger & orchestration | The App | `AzurPilotRunController` polls `/android/update/*` and applies; auto-applies while idle |
| Source fetch | Upstream AzurPilot | `module/api/android_update.py` shallow-git fetches inside the runtime (the rootfs ships without `.git`; it initializes on first update) |
| Instance recovery | Upstream AzurPilot | Reuses the built-in updater's file protocol: `config/reloadalas` (instance list) + `config/webui-dependency-sync-pending` (dependency-sync marker); the parent supervisor restarts the WebUI and `lifecycle.startup()` resumes instances |

### Safe ordering (avoids the source/dist mismatch crash on boot)

1. Pre-flight: if the upstream head's `frontend-<sha>.tar.xz.sha256` is absent
   (CI not finished / offline) the commit counts as **not updatable yet** and the
   current version is kept;
2. Persist the recovery plan (reloadalas + dependency-sync marker) → shallow
   `git fetch --depth 1` + `reset --hard`;
3. Download and verify the dist (sha256 plus source-fingerprint); on failure
   `git reset` rolls the source back;
4. Rewrite `BUILD_MANIFEST` (`azurpilot_commit` etc.; `rootfs_version` untouched —
   see the distinction rules below);
5. Trigger `restart_event`: the parent supervisor finishes dependency sync,
   restarts the WebUI, and instances resume per reloadalas.

### Full redeploy vs hot update

`rootfs_version` is a composite string: `<upstream-commit-12>-<rootfs-input-hash-10>`
(inputs = the host-side build script, overlays, and seeds). A hot update rewrites
`azurpilot_commit` etc. and deliberately leaves `rootfs_version` alone. The App's
full-redeploy criterion therefore splits in two:

- **Input hash (suffix) identical**: the base image is unchanged and the strings
  differ only because the source moved → `RuntimeUpdateCheck.commitOnly` is set,
  no full-update dialog, proot start is not blocked, and the hot-update channel
  owns the difference;
- **Input hash changed**: the Python version, system libraries, or overlay/seed
  content changed → a full redeploy is required and the dialog appears as usual.

Escape hatch: turning off the hot-update toggle restores the full-update prompt
immediately (for forcing a full redeploy when the hot-update channel is unusable).

### App-side contract

- Env `AZURPILOT_ANDROID_DIST_BASE`: mirror prefix + release base, assembled by
  `ProotHost` from the user's download-source setting; effective from the next
  session after a change;
- Settings toggle `hotUpdateEnabled` (on by default); manual entry lives under
  Settings → Runtime;
- Auto-apply conditions: toggle on, an update available, and both the scheduler
  and tools idle.
