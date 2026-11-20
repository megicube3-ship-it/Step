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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;

public class ArmorPunchJumpClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("ArmorPunchJump");
    private static KeyBinding comboKey;
    private static KeyBinding visualKey;
    private static KeyBinding maceKey;

    /** Mode mace: berapa lama (tick) auto-hit aktif setelah mace dipegang. 20 tick = 1 detik. */
    private static final int AUTO_MAX_TICKS = 200;
    /** true = auto-hit berhenti setelah 1x serangan berhasil. false = terus menyerang sampai waktu habis / ditekan lagi. */
    private static final boolean AUTO_STOP_AFTER_HIT = false;
    private static int autoTicksLeft = 0;
    private static boolean releaseJumpNextTick = false;

    /** DEBUG sementara: tulis info ke log (level ERROR supaya muncul di latestlog Amethyst). Tidak ada teks di layar. */
    private static final boolean DEBUG = false;

    private static void dbg(String msg) {
        if (DEBUG) LOG.error("[APJ-DEBUG] " + msg);
    }

    private static void dumpHotbar(ClientPlayerEntity p) {
        StringBuilder sb = new StringBuilder("hotbar:");
        for (int i = 0; i < 9; i++) {
            sb.append(" [").append(i).append("]=").append(path(p.getInventory().getStack(i)));
        }
        sb.append(" selected=").append(p.getInventory().getSelectedSlot());
        dbg(sb.toString());
    }

    /** Panggil klik kiri bawaan lewat mixin dan catat hasilnya. */
    private static void debugAttack(MinecraftClient c, String tag) {
        ClientPlayerEntity p = c.player;
        ItemStack held = p.getMainHandStack();
        Vec3d v0 = p.getVelocity();
        String state = "item=" + path(held) + " onGround=" + p.isOnGround() + " gliding=" + p.isGliding()
                + " usingItem=" + p.isUsingItem() + " food=" + p.getHungerManager().getFoodLevel()
                + " cooldownProgress=" + p.getAttackCooldownProgress(0f);
        if ((Object) c instanceof MinecraftClientInvoker invoker) {
            boolean r = invoker.armorpunchjump$doAttack();
            Vec3d v1 = p.getVelocity();
            dbg(tag + " doAttack result=" + r + " " + state + " velBefore=" + v0 + " velAfter=" + v1
                    + " crosshair=" + (c.crosshairTarget == null ? "null" : c.crosshairTarget.getType()));
        } else {
            dbg(tag + " MIXIN TIDAK TERPASANG (invoker null) " + state);
        }
        if (DEBUG) dbg(tag + " held components: " + held.getComponents());
    }

    /** Mode visual: jeda antar langkah dalam tick (20 tick = 1 detik). Naikkan kalau mau lebih lambat. */
    private static final int STEP_DELAY_TICKS = 2;

    /**
     * false (default): armor dan spear dijalankan di tick yang sama seperti mode G, jadi spear langsung jab tanpa menunggu.
     * true: hotbar berhenti dulu di slot armor, tapi spear baru bisa jab ~1 detik kemudian
     * (game me-reset cooldown spear setiap ganti slot antar tick).
     */
    private static final boolean H_SHOW_ARMOR_STEP = false;
    private static final ArrayDeque<Runnable> steps = new ArrayDeque<>();
    private static int stepCountdown = 0;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("armorpunchjump", "main"));
        comboKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.armorpunchjump.combo",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                category
        ));
        visualKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.armorpunchjump.visual",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                category
        ));
        maceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.armorpunchjump.mace",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                category
        ));
        LOG.info("[ArmorPunchJump] v1.6.0 loaded, keys: G = instan, H = visual, J = armor-spear-mace auto hit");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (releaseJumpNextTick) {
                client.options.jumpKey.setPressed(false);
                releaseJumpNextTick = false;
            }
            if (!steps.isEmpty()) {
                if (stepCountdown > 0) {
                    stepCountdown--;
                } else {
                    Runnable next = steps.poll();
                    stepCountdown = STEP_DELAY_TICKS;
                    next.run();
                }
            }
            autoAttackTick(client);
            while (maceKey.wasPressed()) {
                if (autoTicksLeft > 0) {
                    autoTicksLeft = 0; // ditekan lagi = batalkan auto-hit
                } else {
                    runMaceCombo(client);
                }
            }
            while (comboKey.wasPressed()) {
                LOG.info("[ArmorPunchJump] key pressed");
                runCombo(client);
            }
            while (visualKey.wasPressed()) {
                LOG.info("[ArmorPunchJump] visual key pressed");
                startVisual(client);
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
            dumpHotbar(player);
            debugAttack(client, "G");
            atk = "vanilla";
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
    }

    // ---------- Mode visual: tiap langkah jalan di tick berbeda supaya hotbar & tangan kelihatan ----------

    private static void startVisual(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null || client.currentScreen != null) return;
        if (!steps.isEmpty()) return; // makro visual masih berjalan
        if (H_SHOW_ARMOR_STEP) {
            steps.add(() -> visualArmor(client));
            steps.add(() -> visualSpear(client));
        } else {
            steps.add(() -> visualArmorThenSpear(client));
        }
        steps.add(() -> visualElytra(client));
        steps.add(() -> visualJump(client));
        stepCountdown = 0;
    }

    private static boolean visualReady(MinecraftClient c) {
        if (c.player == null || c.interactionManager == null || c.currentScreen != null) {
            steps.clear();
            return false;
        }
        return true;
    }

    /** Armor lalu spear dalam satu tick (sama seperti mode G) supaya cooldown spear belum ter-reset. */
    private static void visualArmorThenSpear(MinecraftClient c) {
        if (!visualReady(c)) return;
        ClientPlayerEntity p = c.player;
        boolean wasGliding = p.isGliding();
        int slot = findHotbar(p, ArmorPunchJumpClient::isChestArmor);
        if (slot != -1) {
            selectSlot(p, c.getNetworkHandler(), slot);
            c.interactionManager.interactItem(p, Hand.MAIN_HAND);
            // sedang terbang: matikan gliding di server sekarang juga (sama seperti mode G)
            if (wasGliding) sendStartGliding(p, c.getNetworkHandler());
        }
        visualSpear(c);
    }

    private static void visualArmor(MinecraftClient c) {
        if (!visualReady(c)) return;
        ClientPlayerEntity p = c.player;
        int slot = findHotbar(p, ArmorPunchJumpClient::isChestArmor);
        if (slot == -1) return;
        selectSlot(p, c.getNetworkHandler(), slot);
        c.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    /** Batas tunggu cooldown spear di mode visual (tick). Cooldown spear sekitar 23 tick. */
    private static final int SPEAR_WAIT_MAX_TICKS = 60;
    private static int spearWaitTicks = 0;

    /**
     * Spear baru boleh jab kalau cooldown serangannya penuh, dan cooldown itu di-reset setiap ganti slot hotbar.
     * Makro instan (G) lolos karena semua terjadi sebelum game sempat me-reset cooldown.
     * Di mode visual kita pilih spear, lalu jab begitu cooldown penuh (langsung kalau sudah penuh).
     */
    private static void visualSpear(MinecraftClient c) {
        if (!visualReady(c)) return;
        ClientPlayerEntity p = c.player;
        int slot = findHotbar(p, ArmorPunchJumpClient::isSpear);
        if (slot == -1) return;
        selectSlot(p, c.getNetworkHandler(), slot);
        if (p.getAttackCooldownProgress(0f) >= 1.0f) {
            debugAttack(c, "H");
        } else {
            spearWaitTicks = 0;
            steps.addFirst(() -> visualSpearWait(c));
            stepCountdown = 0;
        }
    }

    private static void visualSpearWait(MinecraftClient c) {
        if (!visualReady(c)) return;
        ClientPlayerEntity p = c.player;
        if (p.getAttackCooldownProgress(0f) >= 1.0f || spearWaitTicks >= SPEAR_WAIT_MAX_TICKS) {
            debugAttack(c, "H");
        } else {
            spearWaitTicks++;
            steps.addFirst(() -> visualSpearWait(c));
            stepCountdown = 0;
        }
    }

    private static void visualElytra(MinecraftClient c) {
        if (!visualReady(c)) return;
        ClientPlayerEntity p = c.player;
        int slot = findHotbar(p, ArmorPunchJumpClient::isElytra);
        if (slot == -1) return;
        selectSlot(p, c.getNetworkHandler(), slot);
        c.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private static void visualJump(MinecraftClient c) {
        if (!visualReady(c)) return;
        ClientPlayerEntity p = c.player;
        if (p.isOnGround()) {
            p.jump();
        } else {
            // di udara: tekan jump 1 tick supaya elytra mulai glide lewat jalur bawaan game
            c.options.jumpKey.setPressed(true);
            releaseJumpNextTick = true;
        }
    }

    // ---------- Mode mace: armor -> spear punch -> mace -> auto-hit kalau ada target ----------

    /** Target yang bisa dipukul: makhluk hidup tepat di crosshair (jarak dan hitbox sudah dihitung game). */
    private static Entity validTarget(MinecraftClient c) {
        HitResult hit = c.crosshairTarget;
        if (!(hit instanceof EntityHitResult entityHit)) return null;
        Entity e = entityHit.getEntity();
        if (!(e instanceof LivingEntity living)) return null;
        if (e instanceof ArmorStandEntity) return null;
        if (!living.isAlive() || !living.isAttackable() || living.isInvulnerable()) return null;
        return e;
    }

    private static void runMaceCombo(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (player == null || im == null || client.currentScreen != null) return;

        boolean wasGliding = player.isGliding();

        // 1) Cari armor di hotbar lalu gunakan
        int slot = findHotbar(player, ArmorPunchJumpClient::isChestArmor);
        if (slot != -1) {
            selectSlot(player, nh, slot);
            im.interactItem(player, Hand.MAIN_HAND);
            // sedang terbang: matikan gliding di server sekarang juga (sama seperti mode G)
            if (wasGliding) sendStartGliding(player, nh);
        }

        // 2) Cari spear lalu punch (klik kiri bawaan, tanpa cek mob)
        slot = findHotbar(player, ArmorPunchJumpClient::isSpear);
        if (slot != -1) {
            selectSlot(player, nh, slot);
            debugAttack(client, "J");
        }

        // 3) Cari mace lalu pegang; auto-hit aktif selama ada target yang bisa dipukul
        slot = findHotbar(player, s -> !s.isEmpty() && s.isOf(Items.MACE));
        if (slot != -1) {
            selectSlot(player, nh, slot);
            autoTicksLeft = AUTO_MAX_TICKS;
            autoAttackTick(client);
        } else {
            LOG.info("[ArmorPunchJump] mace tidak ada di hotbar");
        }
    }

    /** Dipanggil tiap tick selama auto-hit aktif. */
    private static void autoAttackTick(MinecraftClient c) {
        if (autoTicksLeft <= 0) return;
        ClientPlayerEntity p = c.player;
        if (p == null || c.interactionManager == null || c.currentScreen != null
                || !p.isAlive() || !p.getMainHandStack().isOf(Items.MACE)) {
            autoTicksLeft = 0;
            return;
        }
        autoTicksLeft--;

        Entity target = validTarget(c);
        if (target == null) return;
        if (p.getAttackCooldownProgress(0f) < 1.0f) return; // tunggu cooldown mace penuh

        boolean hit;
        if ((Object) c instanceof MinecraftClientInvoker invoker) {
            hit = invoker.armorpunchjump$doAttack();
        } else {
            c.interactionManager.attackEntity(p, target);
            p.swingHand(Hand.MAIN_HAND);
            hit = true;
        }
        if (hit && AUTO_STOP_AFTER_HIT) autoTicksLeft = 0;
    }

    private static String yn(boolean b) {
        return b ? "Y" : "N";
    }
}
