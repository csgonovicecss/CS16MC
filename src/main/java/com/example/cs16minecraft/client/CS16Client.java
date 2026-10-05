package com.example.cs16minecraft.client;

import com.example.cs16minecraft.CS16Minecraft;
import com.example.cs16minecraft.assets.CS16AssetManager;
import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.hud.CS16Hud;
import com.example.cs16minecraft.input.CS16Input;
import com.example.cs16minecraft.movement.PlayerMovementAdapter;
import com.example.cs16minecraft.weapon.CS16WeaponManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.InteractionResult;

import java.nio.file.Path;
import java.util.List;

public final class CS16Client implements ClientModInitializer {
    public static final CS16AssetManager ASSETS = new CS16AssetManager();
    public static final PlayerMovementAdapter MOVEMENT = new PlayerMovementAdapter();
    private static boolean keysUnbound;

    @Override
    public void onInitializeClient() {
        CS16Config.load();
        CS16Input.register();
        CS16Hud.register();
        CS16SoundManager.INSTANCE.init(new CS16SoundManager.Source() {
            @Override public Path find(String rel) { return ASSETS.file(rel); }
            @Override public List<String> list(String prefix) { return ASSETS.listFiles(prefix); }
            @Override public void error(String message) { ASSETS.errors().add(message); }
        });
        ASSETS.startAsync();
        ClientTickEvents.START_CLIENT_TICK.register(CS16Client::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(CS16Death::tick); // swaps the death screen before it is drawn

        // Minecraft's own interactions are disabled: no mining, placing, using items or attacking with fists.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> level.isClientSide() ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> level.isClientSide() ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            var block = level.getBlockState(hit.getBlockPos()).getBlock();
            boolean door = block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.TrapDoorBlock;
            return door ? InteractionResult.PASS : InteractionResult.FAIL; // only doors and trapdoors keep their right-click use
        });
        UseItemCallback.EVENT.register((player, level, hand) -> level.isClientSide() ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> level.isClientSide() ? InteractionResult.FAIL : InteractionResult.PASS);

        CS16Minecraft.LOGGER.info("CS16 Minecraft initialised (asset scan running in background)");
    }

    /** CS "fov" is horizontal at 4:3; Minecraft's slider is vertical. 90 -> ~74. Scoped weapons use their zoom FOV (min 30). */
    public static int verticalFov() {
        double h = CS16WeaponManager.INSTANCE.effectiveHFov();
        double v = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(h) / 2) * 0.75));
        return Math.max(30, Math.min(110, (int) Math.round(v)));
    }

    private static void onTick(Minecraft mc) {
        CS16Config cfg = CS16Config.get();
        Options o = mc.options;
        if (o != null) {
            if (!keysUnbound) {
                keysUnbound = true;
                if (cfg.unbindVanillaKeys) CS16Input.unbindVanilla(o);
                o.sensitivity().set(Math.max(0.0, Math.min(1.0, cfg.mouseSensitivity)));
            }
            // The mod owns the camera: no view bobbing, no speed/sprint FOV effects, FOV comes from the config / weapon scope.
            if (o.getCameraType() != CameraType.FIRST_PERSON) o.setCameraType(CameraType.FIRST_PERSON); // F5 disabled
            if (o.bobView().get()) o.bobView().set(false);
            if (o.damageTiltStrength().get() != 0.0) o.damageTiltStrength().set(0.0);      // no hurt camera tilt
            if (o.menuBackgroundBlurriness().get() != 0) o.menuBackgroundBlurriness().set(0); // no menu / death blur
            if (o.fovEffectScale().get() != 0.0) o.fovEffectScale().set(0.0);
            int fov = verticalFov();
            if (o.fov().get() != fov) o.fov().set(fov);
        }
        CS16Input.tick();
        CS16Death.tick(mc);

        if (mc.player != null && o != null) {
            CS16WeaponManager wm = CS16WeaponManager.INSTANCE;
            for (int i = 0; i < 5; i++) while (o.keyHotbarSlots[i].consumeClick()) wm.selectSlot(i + 1);
            while (CS16Input.RELOAD.consumeClick()) wm.requestReload();
            while (CS16Input.QUICK_SWITCH.consumeClick()) wm.quickSwitch();
            while (CS16Input.MIRROR.consumeClick()) wm.toggleMirror();
            com.example.cs16minecraft.weapon.CS16Damage.tickPanic(mc);

            boolean active = MOVEMENT.active(mc.player);
            MOVEMENT.duckWanted = active && mc.mouseHandler.isMouseGrabbed() && CS16Input.CROUCH.isDown();
            if (active) mc.player.setSprinting(false); // vanilla sprint would add a jump boost
            mc.player.setSilent(true);                 // vanilla footsteps off; CS footsteps are played by CS16FootstepManager
        } else {
            MOVEMENT.duckWanted = false;
        }
    }
}
