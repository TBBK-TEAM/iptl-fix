package tbbk.iptlfix;

import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Method;

/**
 * Reflective access to Immersive Portals' duck interface
 * {@code qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket}.
 *
 * Reflection is used on purpose: this mod must load (and stay harmless)
 * even when Immersive Portals is not installed.
 */
public final class IptlDuck {

    private static Method getter;
    private static Method setter;

    static {
        try {
            Class<?> iface = Class.forName("qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket");
            getter = iface.getMethod("ip_getPlayerDimension");
            setter = iface.getMethod("ip_setPlayerDimension", ResourceKey.class);
        } catch (Throwable t) {
            getter = null;
            setter = null;
        }
    }

    private IptlDuck() {
    }

    /** @return true when Immersive Portals' packet duck interface is available. */
    public static boolean isAvailable() {
        return getter != null && setter != null;
    }

    public static Object getDimension(Object packet) throws Exception {
        return getter.invoke(packet);
    }

    public static void setDimension(Object packet, ResourceKey<?> dimension) throws Exception {
        setter.invoke(packet, dimension);
    }
}
