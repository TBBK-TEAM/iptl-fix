package tbbk.iptlfix.mixin;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tbbk.iptlfix.IptlDuck;

/**
 * Immersive Portals 6.0.7 adds an extra "dimension" field to
 * {@link ClientboundPlayerPositionPacket} and writes it unconditionally in the
 * packet's {@code write} hook. That field is only ever filled by Immersive
 * Portals' own overwrite of
 * {@code ServerGamePacketListenerImpl#teleport(double,double,double,float,float,Set)}.
 *
 * Position packets produced by any other code path - most notably the respawn
 * flow - leave the field {@code null}, so writing the packet throws
 * {@code NullPointerException: ... "p_236859_" is null} inside
 * {@code FriendlyByteBuf.writeResourceKey}, the encoder fails and the player is
 * kicked with "Internal Exception: io.netty.handler.codec.EncoderException:
 * Failed to encode packet 'clientbound/minecraft:player_position'".
 *
 * This hook fills the missing dimension with the player's current dimension
 * right before the packet leaves the server. Packets that already carry a
 * dimension are untouched, so normal teleports/portals behave exactly as before.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinServerCommonPacketListenerImpl {

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD")
    )
    private void iptlfix$fillMissingPositionDimension(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerPositionPacket)) {
            return;
        }
        Object self = this;
        if (!(self instanceof ServerGamePacketListenerImpl gameListener)) {
            return;
        }
        if (!IptlDuck.isAvailable() || gameListener.player == null) {
            return;
        }
        try {
            if (IptlDuck.getDimension(packet) == null) {
                IptlDuck.setDimension(packet, gameListener.player.level().dimension());
            }
        } catch (Throwable ignored) {
            // Never break packet sending because of this compatibility hook.
        }
    }
}
