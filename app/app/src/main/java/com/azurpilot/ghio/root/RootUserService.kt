package com.azurpilot.ghio.root

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.IBinder

import com.azurpilot.ghio.third.Ln

object RootUserService {

    private const val TAG = "RootUserService"

    fun create(args: Array<String>): CreatedService? {
        val parsed = ParsedArgs.parse(args) ?: return null

        val userId = parsed.uid / 100000
        val appName = parsed.debugName ?: (parsed.packageName + ":root_service")

        Ln.i(String.format("%s: starting service %s/%s...", TAG, parsed.packageName, parsed.className))
        return try {
            val activityThread = createActivityThread()
            val systemContext = getSystemContext(activityThread)
                ?: throw IllegalStateException("system context is null")

            setAppName(appName, userId)

            val packageContext = createPackageContextAsUser(systemContext, parsed.packageName, userId)
            val application = try {
                makeApplication(activityThread, packageContext)
            } catch (tr: Throwable) {
                // 部分 OEM（如 MIUI）修改了 LoadedApk.makeApplication，在 shell 身份下
                // 因 theme/系统文件缺失导致初始化失败；packageContext 本身可用，直接降级
                Ln.w(TAG + ": makeApplication failed, falling back to packageContext", tr)
                null
            }
            val constructorContext: Context = application ?: packageContext
            val classLoader = constructorContext.classLoader
            val serviceClass = classLoader.loadClass(parsed.className)
            val service = instantiateService(serviceClass, constructorContext)

            CreatedService(service, parsed.token, parsed.packageName, userId)
        } catch (tr: Throwable) {
            Ln.e(String.format("%s: unable to start service %s/%s", TAG, parsed.packageName, parsed.className), tr)
            null
        }
    }

    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    @Throws(Exception::class)
    private fun createActivityThread(): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
        systemMain.isAccessible = true
        return systemMain.invoke(null)
    }

    @Throws(Exception::class)
    private fun getSystemContext(activityThread: Any): Context {
        val method = activityThread.javaClass.getDeclaredMethod("getSystemContext")
        method.isAccessible = true
        return method.invoke(activityThread) as Context
    }

    @Throws(Exception::class)
    private fun makeApplication(activityThread: Any, packageContext: Context): Application {
        val packageInfoField = packageContext.javaClass.getDeclaredField("mPackageInfo")
        packageInfoField.isAccessible = true
        val loadedApk = packageInfoField.get(packageContext)

        val makeApplication = loadedApk.javaClass
            .getDeclaredMethod("makeApplication", Boolean::class.java, Instrumentation::class.java)
        makeApplication.isAccessible = true
        val application = makeApplication.invoke(loadedApk, true, null) as Application

        val initialApplicationField = activityThread.javaClass.getDeclaredField("mInitialApplication")
        initialApplicationField.isAccessible = true
        initialApplicationField.set(activityThread, application)
        return application
    }

    @Throws(Exception::class)
    private fun createPackageContextAsUser(context: Context, packageName: String, userId: Int): Context {
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle: Any = try {
                val ofMethod = userHandleClass.getDeclaredMethod("of", Int::class.java)
                ofMethod.invoke(null, userId)
            } catch (ignored: Throwable) {
                val constructor = userHandleClass.getDeclaredConstructor(Int::class.java)
                constructor.isAccessible = true
                constructor.newInstance(userId)
            }

            val createMethod = Context::class.java.getMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.java,
                userHandleClass
            )
            createMethod.invoke(context, packageName, flags, userHandle) as Context
        } catch (ignored: Throwable) {
            context.createPackageContext(packageName, flags)
        }
    }

    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private fun setAppName(name: String, userId: Int) {
        try {
            val cls = Class.forName("android.ddm.DdmHandleAppName")
            val method = cls.getDeclaredMethod("setAppName", String::class.java, Int::class.java)
            method.invoke(null, name, userId)
        } catch (tr: Throwable) {
            Ln.w(TAG + ": setAppName failed", tr)
        }
    }

    @Throws(Exception::class)
    private fun instantiateService(serviceClass: Class<*>, context: Context): IBinder {
        return try {
            val constructor = serviceClass.getConstructor(Context::class.java)
            constructor.newInstance(context) as IBinder
        } catch (ignored: NoSuchMethodException) {
            val constructor = serviceClass.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance() as IBinder
        }
    }

    data class CreatedService(
        val service: IBinder,
        val token: String,
        val packageName: String,
        val userId: Int,
    )

    private data class ParsedArgs(
        val token: String,
        val packageName: String,
        val className: String,
        val uid: Int,
        val debugName: String?,
    ) {
        companion object {
            fun parse(args: Array<String>): ParsedArgs? {
                var token: String? = null
                var packageName: String? = null
                var className: String? = null
                var debugName: String? = null
                var uid = -1

                for (arg in args) {
                    if (arg.startsWith("--token=")) {
                        token = arg.substring(8)
                    } else if (arg.startsWith("--package=")) {
                        packageName = arg.substring(10)
                    } else if (arg.startsWith("--class=")) {
                        className = arg.substring(8)
                    } else if (arg.startsWith("--uid=")) {
                        uid = arg.substring(6).toInt()
                    } else if (arg.startsWith("--debug-name=")) {
                        debugName = arg.substring(13)
                    }
                }

                if (token == null || packageName == null || className == null || uid < 0) {
                    Ln.e(TAG + ": missing required args")
                    return null
                }
                return ParsedArgs(token, packageName, className, uid, debugName)
            }
        }
    }
}
