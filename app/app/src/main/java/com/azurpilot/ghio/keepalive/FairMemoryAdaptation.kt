package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import timber.log.Timber

/**
 * 适配金标联盟「公平运行内存」机制：接收系统的 TRIM 预警与 KILL 查杀前广播并按协议应答
 *
 * HyperOS / OriginOS 等按金标联盟标准实现的系统会统计应用（同 uid 高优先级进程集合）的
 * PSS 与 Java 堆，超限时先发 TRIM 预警广播（应释放内存），查杀前再发 KILL 广播（应
 * 3 秒内备份现场并应答）。不适配的应用感知不到内存风险，后台留存时长还会被打折——
 * 对本应用就是跑任务期间被杀、虚拟屏被释放，所以必须接。
 *
 * 协议（公平运行内存规范 / HyperOS 开发者文档）：
 * - action：`itgsa.intent.action.TRIM`（预警）/ `itgsa.intent.action.KILL`（查杀前通知）
 * - extras["common"]：notifyType（1000=PSS 超限，2000=Java 堆超限）、notifyId、reason、
 *   action（"trim"/"kill"）、callback（IBinder）
 * - extras["extra"]：notifyType=1000 时含 pss/pssLimit，=2000 时含 heapAlloc/heapCapacity（KB）
 * - 应答：callback.transact(FIRST_CALL_TRANSACTION, data, reply, FLAG_ONEWAY)，data 依次写
 *   notifyType、notifyId、result（0=已处理，1=未处理）与 extra（含 "reply" 说明），3 秒硬限
 *
 * 本应用内存大头是 root uid 的 proot 运行时进程，不计入本应用 PSS；应用侧能释放的量有限，
 * 适配的价值在于按协议应答：系统视本应用为已适配，预警/查杀前会给缓冲而不是直接强杀。
 *
 * Adapts the ITGSA "Fair Running Memory" scheme: receives the system's TRIM warning and
 * pre-kill KILL broadcasts and replies per the protocol.
 *
 * HyperOS / OriginOS and other ROMs implementing the ITGSA standard track the app's (the
 * same-uid high-priority process group's) PSS and Java heap. Past a limit they first send
 * a TRIM warning broadcast (release memory), then a KILL broadcast before killing (back
 * up the scene and reply within 3 seconds). An app that does not adapt never learns of
 * the memory risk and gets its background retention discounted — for this app that means
 * being killed mid-task and losing the virtual display, so the adaptation is mandatory.
 *
 * Protocol (Fair Running Memory spec / HyperOS developer docs):
 * - actions: `itgsa.intent.action.TRIM` (warning) / `itgsa.intent.action.KILL` (pre-kill
 *   notice)
 * - extras["common"]: notifyType (1000 = PSS over limit, 2000 = Java heap over limit),
 *   notifyId, reason, action ("trim"/"kill"), callback (IBinder)
 * - extras["extra"]: notifyType=1000 carries pss/pssLimit, 2000 carries
 *   heapAlloc/heapCapacity (KB)
 * - reply: callback.transact(FIRST_CALL_TRANSACTION, data, reply, FLAG_ONEWAY), with data
 *   writing notifyType, notifyId, result (0 = handled, 1 = not handled) and extra (with a
 *   "reply" note) in that order; 3-second hard deadline
 *
 * The bulk of this app's memory belongs to the root-uid proot runtime process and does
 * not count toward the app's PSS, so little can be freed app-side; the value of adapting
 * lies in replying per protocol: the system then treats the app as adapted and grants a
 * buffer before warning / killing instead of killing outright.
 */
class FairMemoryAdaptation(private val context: Context) {

    private val handlerThread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var registered = false

    /**
     * 对端 callback binder 的死亡监听；对端（系统侧）进程死亡时仅记录日志
     *
     * Death recipient for the system-side callback binder; logs only when the
     * system process dies.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        Timber.d("FairMemory: system callback binder died")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent != null) handle(intent)
        }
    }

    /**
     * 注册 TRIM / KILL 广播接收；幂等，重复调用直接返回
     *
     * 回调统一切到 [handlerThread] 处理；注册失败仅告警，不抛出
     *
     * Registers the TRIM / KILL broadcast receiver; idempotent, repeat calls return
     * immediately.
     *
     * Callbacks are dispatched on [handlerThread]; registration failures are logged,
     * not thrown.
     */
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_TRIM)
            addAction(ACTION_KILL)
        }
        runCatching {
            // 广播由系统服务隐式发出，33+ 必须显式声明 RECEIVER_EXPORTED 才收得到
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, null, handler)
            }
            registered = true
            Timber.i("FairMemory: ITGSA fair running memory adaptation registered")
        }.onFailure {
            Timber.w(it, "FairMemory: failed to register adaptation receiver")
        }
    }

    /**
     * 注销广播接收并退出监听线程；未注册时为空操作
     *
     * Unregisters the broadcast receiver and quits the handler thread; a no-op
     * when not registered.
     */
    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Timber.w(it, "FairMemory: failed to unregister adaptation receiver") }
        registered = false
        handlerThread.quitSafely()
    }

    /**
     * 解析广播并按协议应答；运行在 [handlerThread] 上，须在 3 秒硬限内完成
     *
     * Parses the broadcast and replies per the protocol; runs on [handlerThread] and
     * must complete within the 3-second hard deadline.
     */
    private fun handle(intent: Intent) {
        val common = intent.getBundleExtra(BUNDLE_COMMON)
        if (common == null) {
            Timber.d("FairMemory: %s without common bundle, ignored", intent.action)
            return
        }
        val extra = intent.getBundleExtra(BUNDLE_EXTRA) ?: Bundle.EMPTY
        val notifyType = common.getInt(KEY_NOTIFY_TYPE)
        val notifyId = common.getInt(KEY_NOTIFY_ID)
        val reason = common.getString(KEY_REASON).orEmpty()
        val action = common.getString(KEY_ACTION) ?: intent.action.orEmpty()
        val callback: IBinder? = common.getBinder(KEY_CALLBACK)
        Timber.i(
            "FairMemory: action=%s notifyType=%d notifyId=%d reason=%s %s",
            action, notifyType, notifyId, reason, describe(notifyType, extra),
        )

        // 无 callback 无法应答，系统按超时处理；有则按 action 语义处理并应答
        if (callback == null) return

        runCatching { callback.linkToDeath(deathRecipient, 0) }
            .onFailure { Timber.w(it, "FairMemory: linkToDeath failed") }

        val result = try {
            if (action == ACTION_KILL) {
                saveScene()
            } else {
                trimMemory()
            }
            RESULT_HANDLED
        } catch (e: Exception) {
            Timber.w(e, "FairMemory: handling failed for notifyId=%d", notifyId)
            RESULT_UNHANDLED
        }
        reply(callback, notifyType, notifyId, result)
    }

    /**
     * 预警（action=trim）：释放应用侧内存。运行时大头在 root 侧，这里只能尽力触发回收
     *
     * Trim warning (action=trim): frees app-side memory. The bulk lives in the root
     * runtime, so this can only request a best-effort GC.
     */
    private fun trimMemory() {
        Runtime.getRuntime().gc()
        Timber.i("FairMemory: trim handled, GC requested")
    }

    /**
     * 查杀（action=kill）：3 秒硬限内备份现场。本应用的状态（设置、日志、ALAS 场景文件）
     * 本就实时落盘，查杀后的拉活自愈由保活体系负责，这里补一条现场记录
     *
     * Pre-kill (action=kill): backs up the scene within the 3-second hard deadline.
     * This app's state (settings, logs, ALAS scene files) is already persisted to disk
     * in real time and the keep-alive system handles post-kill recovery, so this only
     * records a scene log entry.
     */
    private fun saveScene() {
        Timber.w("FairMemory: KILL received, scene persisted on disk, keep-alive will recover")
    }

    /** 生成 extra 包的可读摘要供日志使用 / Builds a human-readable summary of the extra bundle for logging. */
    private fun describe(notifyType: Int, extra: Bundle): String = when (notifyType) {
        // vivo 文档的表键名是 heapSize，小米示例代码读的是 heapAlloc，两处都兜一下
        NOTIFY_TYPE_PSS ->
            "pss=${extra.getInt(KEY_PSS)}KB limit=${extra.getInt(KEY_PSS_LIMIT)}KB"
        NOTIFY_TYPE_HEAP -> {
            val alloc = if (extra.containsKey(KEY_HEAP_ALLOC)) {
                extra.getInt(KEY_HEAP_ALLOC)
            } else {
                extra.getInt(KEY_HEAP_SIZE)
            }
            "heapAlloc=${alloc}KB capacity=${extra.getInt(KEY_HEAP_CAPACITY)}KB"
        }
        else -> "notifyType=$notifyType"
    }

    /**
     * 应答系统：FLAG_ONEWAY 立即返回，协议见类 KDoc；3 秒硬限内必须完成
     *
     * Replies to the system; FLAG_ONEWAY returns immediately. See the protocol in
     * the class doc; must complete within the 3-second hard deadline.
     */
    private fun reply(callback: IBinder, notifyType: Int, notifyId: Int, result: Int) {
        val data = android.os.Parcel.obtain()
        val replyParcel = android.os.Parcel.obtain()
        try {
            data.writeInt(notifyType)
            data.writeInt(notifyId)
            data.writeInt(result)
            data.writeBundle(Bundle().apply { putString(KEY_REPLY, REPLY_MESSAGE) })
            callback.transact(
                TRANSACTION_EXCEPTION_REPLY, data, replyParcel, IBinder.FLAG_ONEWAY,
            )
            replyParcel.readException()
            Timber.d("FairMemory: replied notifyId=%d result=%d", notifyId, result)
        } catch (e: Exception) {
            Timber.w(e, "FairMemory: failed to reply notifyId=%d", notifyId)
        } finally {
            data.recycle()
            replyParcel.recycle()
        }
    }

    private companion object {
        const val THREAD_NAME = "fair-memory"

        const val ACTION_TRIM = "itgsa.intent.action.TRIM"
        const val ACTION_KILL = "itgsa.intent.action.KILL"

        const val BUNDLE_COMMON = "common"
        const val BUNDLE_EXTRA = "extra"

        const val KEY_NOTIFY_TYPE = "notifyType"
        const val KEY_NOTIFY_ID = "notifyId"
        const val KEY_REASON = "reason"
        const val KEY_ACTION = "action"
        const val KEY_CALLBACK = "callback"

        const val KEY_PSS = "pss"
        const val KEY_PSS_LIMIT = "pssLimit"
        const val KEY_HEAP_ALLOC = "heapAlloc"
        const val KEY_HEAP_SIZE = "heapSize"
        const val KEY_HEAP_CAPACITY = "heapCapacity"

        /** 预警类型：PSS 超限 / Warning type: PSS over limit. */
        const val NOTIFY_TYPE_PSS = 1000

        /** 预警类型：Java 堆超限 / Warning type: Java heap over limit. */
        const val NOTIFY_TYPE_HEAP = 2000

        /** 应答结果：已处理 / Reply result: handled. */
        const val RESULT_HANDLED = 0

        /** 应答结果：未处理 / Reply result: not handled. */
        const val RESULT_UNHANDLED = 1

        const val KEY_REPLY = "reply"
        const val REPLY_MESSAGE = "The broadcast is received and handled"

        /** 回调接口的 transaction code，规范固定为 FIRST_CALL_TRANSACTION */
        const val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION
    }
}
