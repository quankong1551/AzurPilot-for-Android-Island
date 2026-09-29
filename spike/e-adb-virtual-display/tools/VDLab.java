import java.lang.reflect.Method;

/**
 * 通过反射探测 Android 显示管理隐藏 API 的设备端实验命令行工具。
 *
 * 它只用于 Spike 中比较框架版本和设备厂商的可用方法，不是 app 运行期的特权控制实现。各模式
 * 可枚举候选方法、调用显示电源请求或输出物理显示器 ID。
 *
 * Device-side experimental command-line tool that probes Android display-management hidden APIs
 * through reflection.
 *
 * It is used only by Spikes to compare available methods across framework versions and device
 * vendors, not as the app's runtime privileged-display controller. Its modes enumerate candidates,
 * request display power, or print physical-display IDs.
 */
public final class VDLab {
    /**
     * 运行指定的显示管理实验，并将失败详情写到标准输出。
     *
     * Runs the requested display-management experiment and writes failure details to standard out.
     *
     * @param args 模式及其参数 / mode and its arguments
     */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("[vdlab] ERROR: " + t);
            t.printStackTrace(System.out);
        }
    }

    /**
     * 按模式执行反射调用。
     *
     * `methods` 枚举候选 API；`power` 请求逻辑显示器电源状态；`sfpower` 调用
     * SurfaceControl 物理显示器电源接口；`phyids` 输出可见物理显示器 ID。
     *
     * Executes the reflection operation selected by the mode.
     *
     * `methods` enumerates candidates; `power` requests logical-display power; `sfpower` calls the
     * SurfaceControl physical-display power API; and `phyids` prints visible physical-display IDs.
     *
     * @param args 模式及其参数 / mode and its arguments
     * @throws Exception 反射目标不存在、拒绝访问或调用失败时抛出 / if a reflection target is absent,
     *     inaccessible, or fails during invocation
     */
    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("[vdlab] modes: methods | power <displayId> <on|off> | sfpower <physicalDisplayId> <mode> | phyids");
            return;
        }
        String mode = args[0];
        if ("methods".equals(mode)) {
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance").invoke(null);
            for (Method m : global.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("power") || n.contains("display")) {
                    System.out.println("[vdlab] DMGlobal." + m);
                }
            }
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            for (Method m : sc.getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("power") || n.contains("displaytoken") || n.contains("physicaldisplay")) {
                    System.out.println("[vdlab] SurfaceControl." + m);
                }
            }
        } else if ("power".equals(mode)) {
            int id = Integer.parseInt(args[1]);
            boolean on = Boolean.parseBoolean(args[2]);
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance").invoke(null);
            try {
                Method m = global.getClass().getMethod("requestDisplayPower", int.class, boolean.class);
                Object r = m.invoke(global, id, on);
                System.out.println("[vdlab] requestDisplayPower(int,boolean)(" + id + "," + on + ") -> " + r);
            } catch (NoSuchMethodException e) {
                System.out.println("[vdlab] no (int,boolean) variant");
            }
            try {
                Method m = global.getClass().getMethod("requestDisplayPower", int.class, int.class);
                Object r = m.invoke(global, id, on ? 2 : 1);
                System.out.println("[vdlab] requestDisplayPower(int,int)(" + id + "," + (on ? 2 : 1) + ") -> " + r);
            } catch (NoSuchMethodException e) {
                System.out.println("[vdlab] no (int,int) variant");
            }
        } else if ("sfpower".equals(mode)) {
            long physId = Long.parseLong(args[1]);
            int modeInt = Integer.parseInt(args[2]);
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Class<?> ibinder = Class.forName("android.os.IBinder");
            Object token = sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physId);
            System.out.println("[vdlab] token=" + token);
            Object ok = sc.getMethod("setDisplayPowerMode", ibinder, int.class).invoke(null, token, modeInt);
            System.out.println("[vdlab] setDisplayPowerMode(" + physId + "," + modeInt + ") -> " + ok);
        } else if ("phyids".equals(mode)) {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
            for (long id : ids) {
                System.out.println("[vdlab] physical display id: " + id);
            }
        } else {
            System.out.println("[vdlab] unknown mode: " + mode);
        }
    }
}
