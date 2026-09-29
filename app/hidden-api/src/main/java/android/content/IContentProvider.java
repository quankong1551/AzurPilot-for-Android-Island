package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * IContentProvider 的隐藏 API 编译期桩。仅在编译期镜像框架签名，运行时由系统框架提供实现。
 *
 * Compile-time stub of the hidden API {@code IContentProvider}. Mirrors the framework
 * signature at compile time only; the real implementation is provided by the platform at
 * runtime.
 */
public interface IContentProvider extends IInterface {

    /**
     * 调用内容提供者方法的最初签名。
     *
     * Calls a content provider method; the original signature.
     *
     * @param callingPkg 调用方包名 / the calling package
     * @param method 要调用的方法名 / the provider-defined method to call
     * @param arg 方法的字符串参数，可为 {@code null} / the string method argument, may be
     *            {@code null}
     * @param extras 方法的附加数据，可为 {@code null} / extra arguments, may be {@code null}
     * @return 提供者自定义的结果 / the provider-defined result
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    Bundle call(String callingPkg, String method, String arg, Bundle extras) throws RemoteException;

    /**
     * 在初版签名之上增加 authority，用于区分同一调用方可访问的多个提供者。
     *
     * Calls a content provider method with an added authority to disambiguate between the
     * multiple providers reachable from one caller.
     *
     * @param callingPkg 调用方包名 / the calling package
     * @param authority 目标内容提供者的 authority / the authority of the target provider
     * @param method 要调用的方法名 / the provider-defined method to call
     * @param arg 方法的字符串参数，可为 {@code null} / the string method argument, may be
     *            {@code null}
     * @param extras 方法的附加数据，可为 {@code null} / extra arguments, may be {@code null}
     * @return 提供者自定义的结果 / the provider-defined result
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    Bundle call(String callingPkg, String authority, String method, String arg, Bundle extras)
            throws RemoteException;

    /**
     * 在 authority 签名之上再增加调用方的 attribution 标签，用于归属归因。
     *
     * Calls a content provider method, extending the authority signature with the caller's
     * attribution tag for attribution purposes.
     *
     * @param callingPkg 调用方包名 / the calling package
     * @param attributionTag 调用方的归属标签，可为 {@code null} / the caller's attribution tag,
     *                       may be {@code null}
     * @param authority 目标内容提供者的 authority / the authority of the target provider
     * @param method 要调用的方法名 / the provider-defined method to call
     * @param arg 方法的字符串参数，可为 {@code null} / the string method argument, may be
     *            {@code null}
     * @param extras 方法的附加数据，可为 {@code null} / extra arguments, may be {@code null}
     * @return 提供者自定义的结果 / the provider-defined result
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    Bundle call(String callingPkg, String attributionTag, String authority, String method, String arg,
                Bundle extras) throws RemoteException;

    /**
     * 现代签名：直接携带完整的 AttributionSource，替代零散的包名与归属标签。
     *
     * Calls a content provider method carrying a full AttributionSource, superseding the
     * scattered package name and attribution tag.
     *
     * @param attributionSource 调用方的完整归属来源 / the caller's full attribution source
     * @param authority 目标内容提供者的 authority / the authority of the target provider
     * @param method 要调用的方法名 / the provider-defined method to call
     * @param arg 方法的字符串参数，可为 {@code null} / the string method argument, may be
     *            {@code null}
     * @param extras 方法的附加数据，可为 {@code null} / extra arguments, may be {@code null}
     * @return 提供者自定义的结果 / the provider-defined result
     * @throws RemoteException binder 调用失败时抛出 / if the binder call fails
     */
    Bundle call(AttributionSource attributionSource, String authority, String method, String arg,
                Bundle extras) throws RemoteException;

    /**
     * Binder 桩。仅为编译期签名镜像，方法体不会执行，运行时由框架提供真正的 Stub。
     *
     * Binder stub. A compile-time signature mirror only; the body never runs, the platform
     * supplies the real Stub at runtime.
     */
    abstract class Stub extends Binder implements IContentProvider {
        /**
         * 桩实现，永远抛出；运行时使用框架提供的真正 asInterface。
         *
         * Stub body; always throws. The framework-provided asInterface is used at runtime.
         *
         * @param obj 远端 binder 对象 / the remote binder object
         * @return 永不返回 / never returns
         */
        public static IContentProvider asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
