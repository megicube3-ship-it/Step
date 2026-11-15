package id.armorpunchjump;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

public class ArmorPunchJumpClient implements ClientModInitializer {
    private static KeyBinding comboKey;
    private static boolean releaseJumpNextTick = false;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("armorpunchjump", "main"));
        comboKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.armorpunchjump.combo",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (releaseJumpNextTick) {
                client.options.jumpKey.setPressed(false);
                releaseJumpNextTick = false;
            }
            while (comboKey.wasPressed()) {
                runCombo(client);
            }
        });
    }

    private static String path(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).getPath();
    }

    private static boolean isChestplate(ItemStack s) {
        return !s.isEmpty() && path(s).endsWith("_chestplate");
    }

    private static boolean isSpear(ItemStack s) {
        return !s.isEmpty() && path(s).endsWith("_spear");
    }

    private static boolean isElytra(ItemStack s) {
        return !s.isEmpty() && s.isOf(Items.ELYTRA);
    }

    /** Cari slot hotbar (0-8) yang cocok, -1 kalau tidak ada. */
    private static int findHotbar(ClientPlayerEntity p, java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < 9; i++) {
            if (test.test(p.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private static void runCombo(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null || client.currentScreen != null) return;

        // 1) Pilih chestplate di hotbar lalu gunakan (equip)
        int slot = findHotbar(player, ArmorPunchJumpClient::isChestplate);
        if (slot != -1) {
            player.getInventory().setSelectedSlot(slot);
            im.interactItem(player, Hand.MAIN_HAND);
        }

        // 2) Pindah ke spear lalu punch
        slot = findHotbar(player, ArmorPunchJumpClient::isSpear);
        if (slot != -1) {
            player.getInventory().setSelectedSlot(slot);
            HitResult hit = client.crosshairTarget;
            if (hit instanceof EntityHitResult entityHit) {
                im.attackEntity(player, entityHit.getEntity());
            }
            player.swingHand(Hand.MAIN_HAND);
        }

        // 3) Pindah ke elytra di hotbar lalu gunakan (equip)
        slot = findHotbar(player, ArmorPunchJumpClient::isElytra);
        if (slot != -1) {
            player.getInventory().setSelectedSlot(slot);
            im.interactItem(player, Hand.MAIN_HAND);
        }

        // 4) Jump
        if (player.isOnGround()) {
            player.jump();
        } else {
            // di udara: tekan tombol jump 1 tick supaya elytra mulai glide
            client.options.jumpKey.setPressed(true);
            releaseJumpNextTick = true;
        }
    }
}
