package id.armorpunchjump.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {
    /** Memanggil klik kiri (attack) bawaan Minecraft, sama persis seperti menekan tombol attack. */
    @Invoker("doAttack")
    boolean armorpunchjump$doAttack();
}
