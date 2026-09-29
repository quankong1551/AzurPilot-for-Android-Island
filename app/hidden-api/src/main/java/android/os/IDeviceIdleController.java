package android.os;

/**
 * IDeviceIdleController 的隐藏 API 编译期桩，覆盖 Doze 省电白名单的管理。
 * 仅在编译期镜像框架签名，运行时由系统框架提供实现。
 *
 * Compile-time stub of the hidden API {@code IDeviceIdleController}, covering management of
 * the Doze power-save whitelist. Mirrors the framework signature at compile time only; the
 * real implementation is provided by the platform at runtime.
 */
public interface IDeviceIdleController extends IInterface {

    /**
     * 把应用加入省电（Doze）白名单，使其在设备空闲时不被系统限制。
     *
     * Adds a package to the power-save (Doze) whitelist so the system does not restrict it
     * while the device is idle.
     *
     * @param packageName 要加白的应用包名 / the package to whitelist
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void addPowerSaveWhitelistApp(String packageName) throws RemoteException;

    /**
     * 把应用移出省电（Doze）白名单。
     *
     * Removes a package from the power-save (Doze) whitelist.
     *
     * @param packageName 要移出的应用包名 / the package to remove
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    void removePowerSaveWhitelistApp(String packageName) throws RemoteException;

    /**
     * 查询应用是否在省电（Doze）白名单中。
     *
     * Queries whether a package is on the power-save (Doze) whitelist.
     *
     * @param packageName 要查询的应用包名 / the package to query
     * @return 在白名单中返回 {@code true} / {@code true} if whitelisted
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    boolean isPowerSaveWhitelistApp(String packageName) throws RemoteException;

    /**
     * Binder 桩。仅为编译期签名镜像，方法体不会执行，运行时由框架提供真正的 Stub。
     *
     * Binder stub. A compile-time signature mirror only; the body never runs, the platform
     * supplies the real Stub at runtime.
     */
    abstract class Stub extends Binder implements IDeviceIdleController {
        /**
         * 桩实现，永远抛出；运行时使用框架提供的真正 asInterface。
         *
         * Stub body; always throws. The framework-provided asInterface is used at runtime.
         *
         * @param obj 远端 binder 对象 / the remote binder object
         * @return 永不返回 / never returns
         */
        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
