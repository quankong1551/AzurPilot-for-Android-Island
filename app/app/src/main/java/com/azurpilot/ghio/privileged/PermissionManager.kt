package com.azurpilot.ghio.privileged
import com.azurpilot.ghio.AppDispatchers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.azurpilot.ghio.constant.PrivilegedGrant
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.i18n.uiTextFromFramework
import com.azurpilot.ghio.settings.AppSettingsManager
import rikka.sui.Sui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * 汇总提权状态并代发授权动作；ViewModel 侧唯一的提权入口
 *
 * 代授集合固定为 [PrivilegedGrant.ALL] 全集，不按运行模式挑：用不上的位代授是空操作，
 * 少授反而会在用户切模式时漏掉某一项
 *
 * [RemoteAccessPort] 只汇总两个后端的可用性，不感知持久化；
 * 后端存在哪、什么时候刷新、授权后要不要绑定，都由这一层决定
 *
 * 生命周期：注册为 ProcessLifecycleOwner 的观察者，回前台即 refresh——
 * Shizuku 授权没有回调，只能回来时重读
 *
 * 两个 Port 由构造注入而不是直接点名 object：进程级单例该在 Koin 里装配，
 * 而不是让每个调用点各自去 import 一个全局
 *
 * Aggregates privilege state and forwards grant actions; the ViewModel side's
 * single privilege entry point.
 *
 * The grant set is fixed to the full [PrivilegedGrant.ALL] set, unfiltered by
 * run mode: granting unused bits is a no-op, while granting fewer would drop
 * one when the user switches modes.
 *
 * [RemoteAccessPort] only aggregates both backends' availability and knows
 * nothing of persistence; where the backend choice lives, when to refresh,
 * and whether to bind after a grant are all decided here.
 *
 * Lifecycle: registers as a ProcessLifecycleOwner observer and refreshes on
 * every return to the foreground — Shizuku grants have no callback, so the
 * re-read can only happen on resume.
 *
 * Both ports arrive via constructor injection rather than naming objects
 * directly: process-level singletons belong to Koin wiring, not to every call
 * site importing a global.
 */
class PermissionManager(
    context: Context,
    private val appSettings: AppSettingsManager,
    private val servicePort: PrivilegedServicePort,
    private val accessPort: RemoteAccessPort,
) : PermissionGateway, DefaultLifecycleObserver {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isGranting = MutableStateFlow(false)
    override val isGranting: StateFlow<Boolean> = _isGranting.asStateFlow()

    override val state: StateFlow<RemoteAccessState> = accessPort.state

    override val serviceState: StateFlow<PrivilegedServiceState> = servicePort.serviceState

    /**
     * 看门狗状态：service 连上时 2s 轮询 RemoteService.watchdogState()，断开回 IDLE。
     * flatMapLatest 随连接态切换——断开即停轮询，避免空转
     *
     * 每轮现取服务面而不是扣住连上那一刻的 binder：binder 死了 serviceOrNull 即 null，
     * 轮询当场收摊，不必等连接态那条流转过来
     *
     * Watchdog state: polls RemoteService.watchdogState() every 2 s while the
     * service is connected, back to IDLE when disconnected. flatMapLatest
     * follows the connection state — polling stops the moment it drops, with
     * no idle spinning.
     *
     * Each round grabs the service face fresh instead of holding the binder
     * captured at connect time: once the binder dies serviceOrNull turns null
     * and the polling loop shuts down on the spot, without waiting for the
     * connection-state flow to catch up.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val watchdogState: StateFlow<WatchdogState> = servicePort.serviceState
        .flatMapLatest { state ->
            if (state != PrivilegedServiceState.Connected) {
                flowOf(WatchdogState.IDLE)
            } else {
                flow {
                    while (true) {
                        val service = servicePort.serviceOrNull() ?: break
                        emit(
                            WatchdogState.fromAidl(
                                runCatching { service.watchdogState() }.getOrDefault(0),
                            ),
                        )
                        delay(2_000)
                    }
                    emit(WatchdogState.IDLE)
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, WatchdogState.IDLE)

    /**
     * serviceState 的布尔投影；驱动「连上即代授」的订阅
     *
     * Boolean projection of serviceState; drives the "grant as soon as
     * connected" subscription.
     */
    private val serviceConnected: StateFlow<Boolean> = serviceState
        .map { it == PrivilegedServiceState.Connected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _systemPermissions = MutableStateFlow(readSystemPermissions())
    override val systemPermissions: StateFlow<SystemPermissionState> = _systemPermissions.asStateFlow()

    // readiness 的输入都是流，refresh() 用自增计数踢它重算
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Shizuku 引导结论；随授权态、跳过开关、启动包名与手动刷新即时重算。
     * WhileSubscribed(5s)：UI 不看就不付探测那次 IPC 的代价
     *
     * The Shizuku onboarding verdict; recomputed on grant state, the skip
     * flag, the launch package, and manual refresh. WhileSubscribed(5 s): the
     * probe's IPC cost is only paid while some UI is watching.
     */
    override val readiness: StateFlow<ShizukuReadiness> = combine(
        accessPort.state,
        appSettings.skipShizukuCheck,
        appSettings.shizukuLaunchPackage,
        refreshTrigger,
    ) { remoteState, skipCheck, launchPackage, _ ->
        resolveReadiness(remoteState, skipCheck, launchPackage)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShizukuReadiness(),
    )

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // drop(1)：首值是启动时的当前后端，不该被当成"用户切了后端"而去解绑
        scope.launch {
            appSettings.startupBackend.drop(1).distinctUntilChanged().collect {
                // 绑定是按后端建的，换了后端不断开会连着错的特权进程
                servicePort.unbind()
                refresh()
            }
        }
        // 授权到手就把特权进程连上，用户不必再手动点一次
        scope.launch {
            accessPort.state
                .map { it.isGranted(it.configuredBackend) }
                .distinctUntilChanged()
                .filter { it }
                .collect { servicePort.bind() }
        }
        // 特权进程一上线就代授，省掉用户逐个点系统页
        scope.launch {
            serviceConnected.filter { it }.collect { grantViaPrivileged(PrivilegedGrant.ALL) }
        }
    }

    /**
     * 从 Shizuku 的授权界面切回来时状态已变，但没有回调会通知我们
     *
     * State has already changed by the time the user returns from Shizuku's
     * grant screen, and no callback tells us.
     */
    override fun onResume(owner: LifecycleOwner) {
        refresh()
    }

    /**
     * 重读授权快照、系统权限与 Shizuku 引导结论
     *
     * Re-reads the grant snapshot, system permissions, and the Shizuku
     * onboarding verdict.
     */
    override fun refresh() {
        accessPort.refresh()
        _systemPermissions.value = readSystemPermissions()
        refreshTrigger.update { it + 1 }
    }

    /**
     * 权限卡上那行按钮的快路径：特权进程在线就地代授，不跳系统页
     *
     * 只认那一位授没授成——全集代授里别的位失败与这次点击无关
     *
     * Fast path for the permission-card button: grants in place through the
     * privileged process when online, no system-page detour.
     *
     * Only the requested bit's success counts — other bits failing within the
     * full-set grant are irrelevant to this tap.
     */
    override suspend fun quickGrant(permission: SystemPermission): Boolean {
        val bit = permission.grantBit
        val granted = grantViaPrivileged(bit) ?: return false
        return granted and bit != 0
    }

    /**
     * 用特权身份给自己授权，返回特权进程报回的已授位；特权进程不在线返回 null
     *
     * 这是保活权限的主路径，首页那几个手点入口只是特权进程没起来时的兜底。
     * 走 shell/root 身份直接改 AppOps 与 deviceidle 白名单，用户看不到任何系统弹窗
     *
     * [requested] 上线那次是 [PrivilegedGrant.ALL] 全集（对齐 参考实现，不按运行模式挑），
     * [quickGrant] 传单个位
     *
     * Grants this app privileges with the privileged identity and returns the
     * bits the privileged process reports as granted; null when it is offline.
     *
     * This is the main path for keep-alive permissions; the manual entries on
     * the home screen are only a fallback for when the privileged process is
     * down. Uses the shell/root identity to write AppOps and the deviceidle
     * whitelist directly, with no system dialog visible to the user.
     *
     * On bring-up the request is the full [PrivilegedGrant.ALL] set (aligned
     * with the reference implementation, unfiltered by run mode);
     * [quickGrant] passes a single bit.
     */
    private suspend fun grantViaPrivileged(requested: Int): Int? {
        val granted = withContext(AppDispatchers.IO) {
            runCatching {
                servicePort.serviceOrNull()?.grantPermissions(
                    appContext.packageName,
                    appContext.applicationInfo.uid,
                    requested,
                )
            }.onFailure { Timber.w(it, "Privileged permission grant failed") }.getOrNull()
        }
        Timber.i("Privileged grant result requested=%s granted=%s", requested, granted)
        // 无障碍是异步绑定的，代授返回成功不代表服务已经连上
        if (granted != null && granted and PrivilegedGrant.ACCESSIBILITY != 0) {
            withTimeoutOrNull(ACCESSIBILITY_BIND_TIMEOUT_MS.milliseconds) {
                AccessibilityHelperService.isConnected.first { it }
            } ?: Timber.w("Accessibility service did not connect within timeout after grant")
        }
        refresh()
        return granted
    }

    /**
     * 这些项没有变更回调，只能在 onResume 与手动 refresh 时重读
     *
     * There is no change callback for these, so they are re-read only on
     * onResume and manual refresh.
     */
    private fun readSystemPermissions() = SystemPermissionState(
        notification = SystemPermissionRequester.isGranted(appContext, SystemPermission.Notification),
        batteryWhitelist = SystemPermissionRequester.isGranted(
            appContext,
            SystemPermission.BatteryWhitelist,
        ),
        overlay = SystemPermissionRequester.isGranted(appContext, SystemPermission.Overlay),
        storage = SystemPermissionRequester.isGranted(appContext, SystemPermission.Storage),
        accessibility = SystemPermissionRequester.isGranted(appContext, SystemPermission.Accessibility),
    )

    /**
     * 向当前配置后端发起授权；进行中置 [isGranting]
     *
     * Requests the grant from the configured backend; flips [isGranting] while
     * in flight.
     *
     * @return 授权是否到手 / whether the grant was obtained
     */
    override suspend fun requestRemoteAccess(): Boolean {
        val current = accessPort.refresh()
        if (current.isGranted(current.configuredBackend)) return true
        _isGranting.value = true
        return try {
            accessPort.request(current.configuredBackend)
        } finally {
            _isGranting.value = false
            refresh()
        }
    }

    /**
     * 手动拉起特权进程
     *
     * 自动路径（授权到手即 bind）覆盖不了两种情况：特权进程崩过一次，或用户在系统里
     * 撤掉又重新给了授权。那时状态卡在 Died/Disconnected，没有这个入口就只能重启 app
     *
     * Manually brings up the privileged process.
     *
     * The automatic path (bind as soon as the grant lands) misses two cases:
     * the privileged process crashed once, or the user revoked and re-granted
     * in the system. The state is then stuck at Died/Disconnected, and without
     * this entry point only an app restart would recover.
     */
    override suspend fun bindService(): ServiceBindResult {
        if (serviceState.value == PrivilegedServiceState.Connected) return ServiceBindResult.AlreadyConnected
        val current = accessPort.refresh()
        val backend = current.configuredBackend
        if (!current.isAvailable(backend)) return ServiceBindResult.BackendUnavailable(backend)
        if (!current.isGranted(backend) && !requestRemoteAccess()) {
            return ServiceBindResult.AuthRejected(backend)
        }
        return runCatching { servicePort.bind() }
            .fold(
                onSuccess = { ServiceBindResult.Started },
                onFailure = {
                    Timber.e(it, "Failed to bind privileged process manually")
                    ServiceBindResult.Failed(uiTextFromFramework(it.message))
                },
            )
    }

    /** 解绑特权进程 / unbinds the privileged process */
    override fun unbindService() = servicePort.unbind()

    /**
     * 持久化切换后端；绑定层的解绑与刷新由 startupBackend 的订阅跟进
     *
     * Persists the backend switch; the binding layer's unbind and refresh
     * follow from the startupBackend subscription.
     */
    override suspend fun setBackend(backend: RemoteBackend) {
        if (appSettings.startupBackend.value == backend) return
        appSettings.setStartupBackend(backend)
    }

    /**
     * 持久化「跳过 Shizuku 探测」；此后 readiness 恒为 Ready
     *
     * Persists "skip the Shizuku probe"; readiness then stays Ready.
     */
    override suspend fun skipShizukuCheck() {
        appSettings.setSkipShizukuCheck(true)
    }

    /**
     * 用配置的启动包名拉起 shizuku-m（带用户去授权页）
     *
     * Launches shizuku-m via the configured launch package (to walk the user
     * to its grant screen).
     *
     * @return 拉起是否成功（包名空或拉不起都算失败）
     *   / whether the launch succeeded (a blank package or a failed start both fail)
     */
    fun openShizuku(context: Context): Boolean {
        val launchPackage = appSettings.shizukuLaunchPackage.value
        if (launchPackage.isBlank()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(launchPackage) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { Timber.w(it, "Failed to open Shizuku") }.getOrDefault(false)
    }

    /**
     * 未装 shizuku-m 时唯一的正经出口：跳浏览器到分发页
     *
     * 用户在浏览器里装完切回来，[onResume] 会自动重新探测，不必再点「重新检测」——
     * 在此之前弹窗只写「获取方式见项目 README」，手机上等于没有出口
     *
     * The only real way out when shizuku-m is missing: jump to the
     * distribution page in a browser.
     *
     * Once the user installs it and returns, [onResume] re-probes
     * automatically with no need to tap "re-check" — before this existed the
     * dialog only said "see the project README for how to get it", which on a
     * phone is no way out at all.
     */
    fun openShizukuDownload(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL))
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { Timber.w(it, "Failed to open Shizuku download page") }.getOrDefault(false)
    }

    /**
     * 组装引导结论：跳过或非 Shizuku 后端直接 Ready，Sui 优先报兼容态，
     * 其余走 IO 线程分级探测
     *
     * Assembles the onboarding verdict: skip or a non-Shizuku backend is
     * immediately Ready, Sui reports its compatibility stage first, everything
     * else probes in stages on the IO dispatcher.
     */
    private suspend fun resolveReadiness(
        remoteState: RemoteAccessState,
        skipCheck: Boolean,
        launchPackage: String,
    ): ShizukuReadiness {
        val stage = when {
            // 已跳过就不再付探测那次 IPC 的代价
            skipCheck -> ShizukuReadinessStage.Ready
            remoteState.configuredBackend != RemoteBackend.SHIZUKU -> ShizukuReadinessStage.Ready
            // Sui 在启动时已 init，先报兼容性，别被下面的 shizukuAvailable 抢成 NeedAuth
            ShizukuManager.isSui -> ShizukuReadinessStage.SuiAvailable
            remoteState.shizukuGranted -> ShizukuReadinessStage.Ready
            else -> withContext(AppDispatchers.IO) {
                probeShizukuStage(launchPackage, remoteState.shizukuAvailable)
            }
        }
        return ShizukuReadiness(stage = stage, canSwitchToRoot = remoteState.rootAvailable)
    }

    /**
     * 未授权时的分级探测。服务可用也可能是官方版在提供（同包名），
     * 先按包 label 分 flavor：官方版一律劝换 shizuku-m（离线自连是本项目的根基）
     *
     * Staged probing while ungranted. A live service may still be the official
     * build serving it (same package), so the flavor is resolved first from
     * the package label: the official build is always redirected to shizuku-m
     * (offline self-connection is foundational to this project).
     */
    private fun probeShizukuStage(launchPackage: String, serviceAvailable: Boolean): ShizukuReadinessStage {
        val sui = runCatching { Sui.init(appContext.packageName) }.getOrDefault(false)
        if (sui) return ShizukuReadinessStage.SuiAvailable
        val available = serviceAvailable || ShizukuManager.isShizukuAvailable()
        when (detectShizukuFlavor(launchPackage)) {
            ShizukuFlavor.OFFICIAL -> return ShizukuReadinessStage.OfficialConflict
            ShizukuFlavor.MOD -> return if (available) {
                ShizukuReadinessStage.NeedAuth
            } else {
                ShizukuReadinessStage.NotRunning
            }

            ShizukuFlavor.NONE -> Unit
        }
        // 包查不到但服务活着（launchPackage 被改过的怪局）：能用就先走授权
        return if (available) ShizukuReadinessStage.NeedAuth else ShizukuReadinessStage.NotInstalled
    }

    /**
     * 包名下同包异构：官方版 label 是 "Shizuku"，shizuku-m 的 app_name 改成了 "Shizuku-m"（SHIZUKU-M.md）
     *
     * Same package, different builds: the official label is "Shizuku" while
     * shizuku-m renames app_name to "Shizuku-m" (see SHIZUKU-M.md).
     */
    private fun detectShizukuFlavor(packageName: String): ShizukuFlavor {
        if (packageName.isBlank()) return ShizukuFlavor.NONE
        return try {
            val pm = appContext.packageManager
            val label = pm.getPackageInfo(packageName, 0).applicationInfo?.loadLabel(pm)?.toString()
            if (label?.contains(MOD_LABEL_MARK, ignoreCase = true) == true) {
                ShizukuFlavor.MOD
            } else {
                ShizukuFlavor.OFFICIAL
            }
        } catch (_: PackageManager.NameNotFoundException) {
            ShizukuFlavor.NONE
        }
    }

    /**
     * 引导用户卸官方版：系统卸载确认框，卸完回来自动 refresh 进 NotInstalled 引导
     *
     * Walks the user through uninstalling the official build via the system
     * confirm dialog; once it is gone the automatic refresh on return lands in
     * the NotInstalled guidance.
     */
    fun uninstallShizuku(context: Context): Boolean {
        val launchPackage = appSettings.shizukuLaunchPackage.value
        if (launchPackage.isBlank()) return false
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$launchPackage"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { Timber.w(it, "Failed to launch uninstall") }.getOrDefault(false)
    }

    private companion object {
        /**
         * 系统绑定无障碍服务是异步的，3 秒等不到就当没连上，不卡住授权流程
         *
         * The system binds the accessibility service asynchronously; after 3 s
         * without a connection it is treated as unbound, never blocking the
         * grant flow.
         */
        const val ACCESSIBILITY_BIND_TIMEOUT_MS = 3_000L

        /**
         * shizuku-m 的应用名（官方版是 "Shizuku"），flavor 判别标记
         *
         * shizuku-m's app name (the official build is "Shizuku"); the flavor
         * discriminator.
         */
        const val MOD_LABEL_MARK = "Shizuku-m"

        /**
         * shizuku-m 分发页；用 latest 而非固定版本号，出包时不必回来改 App
         *
         * The shizuku-m distribution page; `latest` instead of a pinned
         * version, so a release never requires an app-side edit.
         */
        const val SHIZUKU_DOWNLOAD_URL = "https://github.com/wess09/shizuku-m/releases/latest"
    }
}

