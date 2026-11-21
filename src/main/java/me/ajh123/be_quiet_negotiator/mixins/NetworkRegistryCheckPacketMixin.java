package me.ajh123.be_quiet_negotiator.mixins;

import me.ajh123.be_quiet_negotiator.BeQuietNegotiator;
import me.ajh123.be_quiet_negotiator.ClientConfig;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips NeoForge's outgoing payload validation while connected to a vanilla server.
 * <p>
 * Kept separate from {@link NetworkRegistryMixin} because on 1.21.8+ that mixin targets
 * {@code ClientNetworkRegistry}, while {@code checkPacket} still lives on {@link NetworkRegistry}.
 */
@Mixin(NetworkRegistry.class)
public class NetworkRegistryCheckPacketMixin {
    @Inject(
            method = "checkPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V",
            at = @At("HEAD"),
            remap = false,
            cancellable = true
    )
    private static void checkPacket(Packet<?> packet, ClientCommonPacketListener listener, CallbackInfo ci) {
        // If we are connected to a vanilla server, we don't need to check the packet.
        if (ClientConfig.bypassNegotiationErrors() && BeQuietNegotiator.isConnectedToVanillaServer) {
            ci.cancel();
        }
    }
}
