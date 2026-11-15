package id.armorpunchjump;

import id.armorpunchjump.mixin.MinecraftClientInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
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
        LOG.info("[ArmorPunchJump] v1.3.0 loaded, default key = G");

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

    /** Armor dada: item apa pun untuk slot dada selain elytra. */
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

    /** Pilih slot hotbar di client DAN langsung kasih tahu server (supaya server tidak ketinggalan). */
    private static void selectSlot(ClientPlayerEntity p, ClientPlayNetworkHandler nh, int slot) {
        p.getInventory().setSelectedSlot(slot);
        if (nh != null) nh.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    /** Kirim perintah "start gliding" ke server (di server ini berfungsi sebagai toggle kalau sudah gliding). */
    private static void sendStartGliding(ClientPlayerEntity p, ClientPlayNetworkHandler nh) {
        if (nh != null) nh.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private static void runCombo(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (player == null || im == null) return;
        if (client.currentScreen != null) {
            LOG.info("[ArmorPunchJump] skipped, screen open");
            return;
        }

        boolean wasGliding = player.isGliding();
        boolean okArmor = false, okSpear = false, okElytra = false;
        String atk = "-";

        // 1) Cari armor di hotbar lalu gunakan (elytra terlepas)
        int slot = findHotbar(player, ArmorPunchJumpClient::isChestArmor);
        if (slot != -1) {
            selectSlot(player, nh, slot);
            im.interactItem(player, Hand.MAIN_HAND);
            okArmor = true;
            // Sedang terbang: matikan gliding di server sekarang juga
            // (tanpa ini server baru berhenti gliding di tick berikutnya, jadi lunge tidak jalan)
            if (wasGliding) sendStartGliding(player, nh);
        }

        // 2) Cari spear lalu attack (klik kiri bawaan game, tanpa cek mob)
        slot = findHotbar(player, ArmorPunchJumpClient::isSpear);
        if (slot != -1) {
            selectSlot(player, nh, slot);
            if ((Object) client instanceof MinecraftClientInvoker invoker) {
                invoker.armorpunchjump$doAttack();
                atk = "vanilla";
            } else {
                HitResult hit = client.crosshairTarget;
                if (hit instanceof EntityHitResult entityHit) {
                    im.attackEntity(player, entityHit.getEntity());
                }
                player.swingHand(Hand.MAIN_HAND);
                atk = "fallback";
            }
            okSpear = true;
        }

        // 3) Cari elytra di hotbar lalu gunakan
        slot = findHotbar(player, ArmorPunchJumpClient::isElytra);
        if (slot != -1) {
            selectSlot(player, nh, slot);
            im.interactItem(player, Hand.MAIN_HAND);
            okElytra = true;
        }

        // 4) Jump / terbang lagi
        String jump;
        if (wasGliding && okArmor && okElytra) {
            // tadi terbang: nyalakan gliding lagi di server (client memang masih gliding)
            sendStartGliding(player, nh);
            jump = "glide";
        } else if (player.isOnGround()) {
            player.jump();
            jump = "jump";
        } else {
            // di udara tapi belum gliding: tekan tombol jump 1 tick supaya elytra mulai glide
            client.options.jumpKey.setPressed(true);
            releaseJumpNextTick = true;
            jump = "key";
        }

        String msg = "Combo: armor=" + yn(okArmor) + " spear=" + yn(okSpear)
                + " atk=" + atk + " elytra=" + yn(okElytra)
                + " fly=" + yn(wasGliding) + " end=" + jump;
        LOG.info("[ArmorPunchJump] " + msg);
        player.sendMessage(Text.literal(msg), true);
    }

    private static String yn(boolean b) {
        return b ? "Y" : "N";
    }
}
