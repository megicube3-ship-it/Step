package id.armorpunchjump;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import id.armorpunchjump.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArmorPunchJumpClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("ArmorPunchJump");
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
        LOG.info("[ArmorPunchJump] v1.2.0 loaded, default key = G");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (releaseJumpNextTick) {
                client.options.jumpKey.setPressed(false);
                releaseJumpNextTick = false;
            }
            while (comboKey.wasPressed()) {
                LOG.info("[ArmorPunchJump] key pressed");
                runCombo(client);
            }
        });
    }

    private static String path(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).getPath();
    }

    /** Chestplate / baju armor: item apa pun yang dipakai di slot dada, kecuali elytra. */
    private static boolean isChestArmor(ItemStack s) {
        if (s.isEmpty() || s.isOf(Items.ELYTRA)) return false;
        EquippableComponent eq = s.get(DataComponentTypes.EQUIPPABLE);
        if (eq != null && eq.slot() == EquipmentSlot.CHEST) return true;
        return path(s).endsWith("_chestplate");
    }

    private static boolean isSpear(ItemStack s) {
        return !s.isEmpty() && path(s).endsWith("_spear");
    }

    private static boolean isElytra(ItemStack s) {
        return !s.isEmpty() && s.isOf(Items.ELYTRA);
    }

    private static int findHotbar(ClientPlayerEntity p, java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < 9; i++) {
            if (test.test(p.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private static void runCombo(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null) return;
        if (client.currentScreen != null) {
            LOG.info("[ArmorPunchJump] skipped, screen open");
            return;
        }

        boolean okArmor = false, okSpear = false, okElytra = false;
        String atk = "-";

        // 1) Pilih armor (dada) di hotbar lalu gunakan
        int slot = findHotbar(player, ArmorPunchJumpClient::isChestArmor);
        if (slot != -1) {
            player.getInventory().setSelectedSlot(slot);
            im.interactItem(player, Hand.MAIN_HAND);
            okArmor = true;
        }

        // 2) Pindah ke spear lalu attack (klik kiri bawaan game, tanpa cek mob)
        slot = findHotbar(player, ArmorPunchJumpClient::isSpear);
        if (slot != -1) {
            player.getInventory().setSelectedSlot(slot);
            if ((Object) client instanceof MinecraftClientInvoker invoker) {
                invoker.armorpunchjump$doAttack();
                atk = "vanilla";
            } else {
                // cadangan kalau mixin gagal dipasang
                HitResult hit = client.crosshairTarget;
                if (hit instanceof EntityHitResult entityHit) {
                    im.attackEntity(player, entityHit.getEntity());
                }
                player.swingHand(Hand.MAIN_HAND);
                atk = "fallback";
            }
            okSpear = true;
        }

        // 3) Pindah ke elytra di hotbar lalu gunakan
        slot = findHotbar(player, ArmorPunchJumpClient::isElytra);
        if (slot != -1) {
            player.getInventory().setSelectedSlot(slot);
            im.interactItem(player, Hand.MAIN_HAND);
            okElytra = true;
        }

        // 4) Jump
        if (player.isOnGround()) {
            player.jump();
        } else {
            client.options.jumpKey.setPressed(true);
            releaseJumpNextTick = true;
        }

        String msg = "Combo: armor=" + yn(okArmor) + " spear=" + yn(okSpear)
                + " atk=" + atk + " elytra=" + yn(okElytra);
        LOG.info("[ArmorPunchJump] " + msg);
        player.sendMessage(Text.literal(msg), true);
    }

    private static String yn(boolean b) {
        return b ? "Y" : "N";
    }
}
