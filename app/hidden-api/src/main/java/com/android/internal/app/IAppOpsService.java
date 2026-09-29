package com.android.internal.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * IAppOpsService 的隐藏 API 编译期桩，覆盖应用操作（AppOps）模式与查询。
 * 仅在编译期镜像框架签名，运行时由系统框架提供实现。
 *
 * Compile-time stub of the hidden API {@code IAppOpsService}, covering AppOps mode setting
 * and querying. Mirrors the framework signature at compile time only; the real implementation
 * is provided by the platform at runtime.
 */
public interface IAppOpsService extends IInterface {

    /**
     * 设置某应用在某操作码上的模式。
     *
     * Sets the mode of an operation code for a package.
     *
     * @param code 操作码 / the operation code
     * @param uid 目标应用的 uid / the uid of the target package
     * @param packageName 目标应用包名 / the target package
     * @param mode 要写入的模式 / the mode to set
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void setMode(int code, int uid, String packageName, int mode) throws RemoteException;

    /**
     * 设置某 uid 在某操作码上的整体模式（作用于该 uid 下全部应用）。
     *
     * Sets the mode of an operation code for an entire uid, applying to every package in it.
     *
     * @param code 操作码 / the operation code
     * @param uid 目标 uid / the target uid
     * @param mode 要写入的模式 / the mode to set
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void setUidMode(int code, int uid, int mode) throws RemoteException;

    /**
     * 查询某应用在某操作码上的当前模式。
     *
     * Queries the current mode of an operation code for a package.
     *
     * @param code 操作码 / the operation code
     * @param uid 目标应用的 uid / the uid of the target package
     * @param packageName 目标应用包名 / the target package
     * @return 当前模式值 / the current mode value
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    int checkOperation(int code, int uid, String packageName) throws RemoteException;

    /**
     * Binder 桩。仅为编译期签名镜像，方法体不会执行，运行时由框架提供真正的 Stub。
     *
     * Binder stub. A compile-time signature mirror only; the body never runs, the platform
     * supplies the real Stub at runtime.
     */
    abstract class Stub extends Binder implements IAppOpsService {

        /**
         * 桩实现，永远抛出；运行时使用框架提供的真正 asInterface。
         *
         * Stub body; always throws. The framework-provided asInterface is used at runtime.
         *
         * @param obj 远端 binder 对象 / the remote binder object
         * @return 永不返回 / never returns
         */
        public static IAppOpsService asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
