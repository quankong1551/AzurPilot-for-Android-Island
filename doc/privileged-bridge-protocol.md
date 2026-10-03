# 特权桥协议 / Privileged bridge protocol

> 本文定义 App Runtime 客户端与特权进程内本地桥之间当前可验证的线协议。
> This document defines the currently verifiable wire protocol between the App Runtime client and the local bridge in the privileged process.

## 中文

### 范围与边界

桥服务实现位于 `app/app/src/main/java/com/azurpilot/ghio/remote/internal/BridgeServer.kt`，只监听 `127.0.0.1:22301`。它不是公网服务：不要把端口转发、监听地址变更或认证方案视为兼容的小改动。特权进程拥有屏幕捕获、输入注入和 shell 执行能力，任何新端点都必须先审查最小权限、参数约束、超时与输出上限。

协议兼容性由当前服务实现和本文件共同定义。不存在可作为规范依据的历史 m0 脚本，也不得宣称与未固定的上游补丁逐字节兼容。

### 帧格式

1. 客户端向 socket 写入一行 UTF-8 JSON，并以 `\n` 结尾。请求行上限为 64 KiB；超限连接会断开。
2. 除 `screencap` 的成功负载外，服务向客户端写入一行 UTF-8 JSON 并以 `\n` 结尾。
3. 服务从请求读取可选 `id`，并在每个成功或错误 JSON 响应中写回同一 `id`。缺失时写 JSON `null`。
4. `screencap` 成功时，服务先写 JSON 元数据行，再在同一输出流紧接写入 `length` 字节的原始 BGR888 图像数据。客户端必须先完整读取该 JSON 行并按 `length` 读完字节，才能发送或读取下一条协议消息。
5. 无效 JSON 会收到一次带 `ok=false` 的错误响应，随后连接关闭。其他单请求异常通常返回错误 JSON，连接保持打开；客户端仍必须容忍断开。

### 端点

| 方法 | 必填字段 | 成功响应 | 约束 |
|---|---|---|---|
| `ping` | 无 | `ok`、`pong`、`displayId`、`capture`、`uptime` | 诊断端点，不表示目标应用已经可交互。 |
| `screencap` | 无 | `ok`、`width`、`height`、`channels=3`、`length`、`frames`，后接 BGR888 字节 | 捕获不可用或尺寸不匹配时返回错误 JSON，不发送原始字节。`frames` 是本轮帧缓冲生命周期内已发布帧的递增序号：相邻两次 screencap 的值不变即画面冻结（如目标进程被 ROM 冻结）。客户端可忽略该字段。 |
| `click` | `x`、`y` | `ok=true` | 使用当前虚拟显示；无活动显示或触摸注入失败时返回错误。 |
| `swipe` | `x1`、`y1`、`x2`、`y2` | `ok=true` | 可选 `duration` 为毫秒，负值按 0 处理。 |
| `shell` | `cmd` | `ok`、`code`、`stdout`、`stderr` | 可选 `timeout` 为秒；每个输出流最多保留 64 KiB，超时会返回错误。 |

未知或缺失 `method` 返回 `ok=false`。请求参数类型错误由 JSON 访问器抛出后返回错误响应；客户端不得依赖具体错误文案。

### 并发与修改规则

服务为每个客户端使用一个守护线程。`screencap`、`click` 和 `swipe` 共享设备锁，因而会相互串行；`shell` 不占用该锁。不要把该实现细节当作客户端并发授权，客户端应在自身侧保持单个设备操作序列有序。

修改协议前必须：

1. 更新本文件和 `BridgeServer` 的 KDoc。
2. 保持 `screencap` 元数据与原始字节的边界明确，或引入版本化协商。
3. 为新增字段采用向后兼容的可选字段，除非同步升级所有已知客户端。
4. 复核新命令是否扩大特权进程的 shell、输入或屏幕访问面。
5. 在真实设备上验证正常响应、错误响应、客户端断开和超时路径。

## English

### Scope and boundary

The bridge implementation is `app/app/src/main/java/com/azurpilot/ghio/remote/internal/BridgeServer.kt` and listens only on `127.0.0.1:22301`. It is not a public network service. Treat port forwarding, bind-address changes, and authentication changes as compatibility-sensitive work. The privileged process can capture screens, inject input, and execute shell commands, so every new endpoint requires a least-privilege review of parameters, timeouts, and output caps.

The current service implementation and this document define compatibility. No historical m0 script is a normative source, and the project must not claim byte-for-byte compatibility with an unpinned upstream patch.

### Frame format

1. The client writes one UTF-8 JSON request terminated by `\n`. Requests are capped at 64 KiB; an over-limit request disconnects the client.
2. Except for a successful `screencap` payload, the service writes one UTF-8 JSON response terminated by `\n`.
3. The service reads an optional request `id` and echoes that `id` in every success or error JSON response. A missing id becomes JSON `null`.
4. For successful `screencap`, the service writes a JSON metadata line, then immediately writes `length` bytes of raw BGR888 image data on the same stream. The client must finish reading the JSON line and exactly `length` bytes before sending or reading another protocol message.
5. Invalid JSON receives one `ok=false` error response and then the connection closes. Other per-request exceptions normally return an error JSON response while the connection remains open, but clients must still tolerate disconnects.

### Endpoints

| Method | Required fields | Success response | Constraints |
|---|---|---|---|
| `ping` | None | `ok`, `pong`, `displayId`, `capture`, `uptime` | Diagnostic only; it does not prove that the target app is interactive. |
| `screencap` | None | `ok`, `width`, `height`, `channels=3`, `length`, `frames`, followed by BGR888 bytes | An unavailable capture or size mismatch returns error JSON and no raw bytes. `frames` is the increasing sequence of frames published within the current frame-buffer lifetime: identical values across consecutive screencaps mean a frozen picture (for example, the target process frozen by the ROM). Clients may ignore the field. |
| `click` | `x`, `y` | `ok=true` | Uses the current virtual display; no active display or failed injection is an error. |
| `swipe` | `x1`, `y1`, `x2`, `y2` | `ok=true` | Optional `duration` is milliseconds; negative values become 0. |
| `shell` | `cmd` | `ok`, `code`, `stdout`, `stderr` | Optional `timeout` is seconds; each output stream retains at most 64 KiB, and timeout returns an error. |

An unknown or missing `method` returns `ok=false`. Type errors in request fields become an error response; clients must not depend on exact error wording.

### Concurrency and change rules

The service uses one daemon thread per client. `screencap`, `click`, and `swipe` share a device lock and therefore serialize with one another; `shell` does not use that lock. Do not treat this implementation detail as permission for client-side concurrency. A client should keep one device-operation sequence ordered.

Before changing the protocol:

1. Update this document and the `BridgeServer` KDoc.
2. Keep the `screencap` metadata/raw-byte boundary explicit, or introduce versioned negotiation.
3. Make new fields optional and backward compatible unless every known client upgrades together.
4. Review whether a new command expands the privileged process's shell, input, or screen-access surface.
5. Verify success, error, client-disconnect, and timeout paths on a physical device.
