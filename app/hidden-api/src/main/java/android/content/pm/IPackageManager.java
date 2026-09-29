package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * IPackageManager 的隐藏 API 编译期桩，覆盖权限授予/撤销与权限标志位查询。
 * 仅在编译期镜像框架签名，运行时由系统框架提供实现。
 *
 * Compile-time stub of the hidden API {@code IPackageManager}, covering runtime permission
 * grant/revoke and permission-flags queries. Mirrors the framework signature at compile time
 * only; the real implementation is provided by the platform at runtime.
 */
public interface IPackageManager extends IInterface {

    /**
     * 向指定用户授予一条运行时权限。
     *
     * Grants a runtime permission to a user.
     *
     * @param packageName 目标应用包名 / the target package
     * @param permissionName 要授予的权限名 / the permission to grant
     * @param userId 目标用户 / the target user
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void grantRuntimePermission(String packageName, String permissionName, int userId) throws RemoteException;

    /**
     * 撤销指定用户的一条运行时权限。
     *
     * Revokes a runtime permission from a user.
     *
     * @param packageName 目标应用包名 / the target package
     * @param permissionName 要撤销的权限名 / the permission to revoke
     * @param userId 目标用户 / the target user
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void revokeRuntimePermission(String packageName, String permissionName, int userId) throws RemoteException;

    /**
     * 查询某权限在某应用上的标志位。
     *
     * Queries the permission flags currently set for a package.
     *
     * @param permissionName 权限名 / the permission name
     * @param packageName 目标应用包名 / the target package
     * @param userId 目标用户 / the target user
     * @return 权限标志位掩码 / the permission flag bitmask
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    int getPermissionFlags(String permissionName, String packageName, int userId) throws RemoteException;

    /**
     * 按 mask/values 更新某权限在某应用上的标志位。
     *
     * Updates the permission flags for a package via a mask/value pair.
     *
     * @param permissionName 权限名 / the permission name
     * @param packageName 目标应用包名 / the target package
     * @param flagMask 要修改的标志位掩码 / the mask of flags to modify
     * @param flagValues 掩码范围内要写入的新值 / the new values inside the mask
     * @param userId 目标用户 / the target user
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void updatePermissionFlags(String permissionName, String packageName, int flagMask, int flagValues, int userId) throws RemoteException;

    /**
     * 检查某应用在某用户下是否持有指定权限。
     *
     * Checks whether a package holds a permission for a user.
     *
     * @param permName 权限名 / the permission name
     * @param pkgName 目标应用包名 / the target package
     * @param userId 目标用户 / the target user
     * @return 授予或拒绝的判定值 / the grant or deny verdict
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    int checkPermission(String permName, String pkgName, int userId) throws RemoteException;

    /**
     * Binder 桩。仅为编译期签名镜像，方法体不会执行，运行时由框架提供真正的 Stub。
     *
     * Binder stub. A compile-time signature mirror only; the body never runs, the platform
     * supplies the real Stub at runtime.
     */
    abstract class Stub extends Binder implements IPackageManager {

        /**
         * 桩实现，永远抛出；运行时使用框架提供的真正 asInterface。
         *
         * Stub body; always throws. The framework-provided asInterface is used at runtime.
         *
         * @param obj 远端 binder 对象 / the remote binder object
         * @return 永不返回 / never returns
         */
        public static IPackageManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
