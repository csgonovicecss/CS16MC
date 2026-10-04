package com.example.cs16minecraft.weapon;

import com.example.cs16minecraft.assets.GoldSrcModelLoader.Event;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.ModelData;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.Sequence;
import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.movement.GoldSrcMovementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The weapon state machine: selection, deploy, fire (semi/auto/burst), reload (magazine and shell), scope,
 * silencer, knife, grenades, recoil, spread and animation playback. Runs once per rendered frame so fire rates
 * are not quantised to Minecraft's 20 Hz ticks.
 */
public final class CS16WeaponManager {
    public static final CS16WeaponManager INSTANCE = new CS16WeaponManager();

    public enum State { DEPLOY, IDLE, RELOAD, SHELL_START, SHELL_INSERT, SHELL_END, ATTACH, GRENADE_PULL, GRENADE_HOLD, GRENADE_THROW }

    public CS16Weapon current = CS16WeaponRegistry.byId("usp");
    private CS16Weapon last = CS16WeaponRegistry.byId("knife");
    public State state = State.DEPLOY;
    public final CS16GrenadeManager grenades = new CS16GrenadeManager();
    public double menuUntil;
    public double inaccuracy;

    private final Map<String, int[]> ammo = new HashMap<>();
    private final Map<Integer, CS16Weapon> lastInSlot = new HashMap<>();

    private Sequence seq;
    private double seqStart, lastEventFrame = -1;
    private boolean seqLoop;

    private double nextAttack, readyAt, phaseEnd, reloadEnd, throwAt;
    private boolean prevAttack, prevSecondary, reloadQueued, releaseQueued, thrown;
    private boolean silenced = true, burstMode, altFire;
    private boolean needInit = true;
    private int scope, resumeScope = -1, burstLeft;
    private double attachStart, attachDur, lastAlert;
    private double burstNext, spray, lastShot, muzzleUntil, kick, punchPitch, punchYaw;

    private CS16WeaponManager() {
        for (CS16Weapon w : CS16WeaponRegistry.all()) ammo.put(w.id, new int[]{w.clip, w.clip * w.reserveMult});
        lastInSlot.put(2, current);
    }

    public static double now() { return System.nanoTime() / 1.0e9; }

    // ------------------------------------------------------------------ accessors for HUD / renderer

    public int scope() { return scope; }
    public int clip() { return ammo().length > 0 ? ammo()[0] : 0; }
    public int reserve() { return ammo()[1]; }
    public boolean silenced() { return silenced; }
    public boolean burstMode() { return burstMode; }
    public boolean viewmodelVisible() { return scope == 0; }
    public boolean muzzleActive() { return now() < muzzleUntil; }
    public double kick() { return kick; }
    public boolean sequenceLoops() { return seqLoop; }
    public Sequence currentSequence() { return seq; }
    public ModelData currentModel() { return CS16Client.ASSETS.model("models/" + current.model + ".mdl"); }
    /**
     * Picks the sub-model of a body part. For silencer weapons the part that holds the silencer is chosen by name and
     * switched mid-animation while attaching / detaching, so the silencer appears and disappears with the animation.
     */
    public int bodyChoice(List<com.example.cs16minecraft.assets.GoldSrcModelLoader.SubModel> part) {
        if (part.size() < 2 || !current.silencer) return 0;
        int sil = -1;
        for (int i = 0; i < part.size(); i++) {
            String n = part.get(i).name.toLowerCase();
            if (n.contains("sil") && !n.contains("unsil") && !n.contains("nosil") && !n.contains("no_sil")) sil = i;
        }
        if (sil < 0) return 0;
        if (silencerVisible()) return sil;
        return sil == 0 ? 1 : 0;
    }

    private boolean silencerVisible() {
        if (state == State.ATTACH) {
            double pr = Math.max(0, Math.min(1, (now() - attachStart) / Math.max(0.1, attachDur)));
            return silenced ? pr < 0.55 : pr > 0.45;
        }
        return silenced;
    }

    public boolean mirrored() { return CS16Config.get().viewmodel.mirrored; }

    /** H: swap the viewmodel to the other side of the screen and replay the draw animation. */
    public void toggleMirror() {
        if (state == State.GRENADE_PULL || state == State.GRENADE_HOLD || state == State.GRENADE_THROW) return;
        CS16Config.get().viewmodel.mirrored = !CS16Config.get().viewmodel.mirrored;
        CS16Config.save();
        scope = 0;
        resumeScope = -1;
        burstLeft = 0;
        reloadQueued = false;
        needInit = true; // next update replays the draw animation
    }

    private double alertRadius(CS16Weapon w) {
        double r = Math.min(60.0, 14.0 + w.damage * 0.35 * (w.pellets > 1 ? 1.6 : 1.0));
        return (w.silencer && silenced) ? r * 0.25 : r;
    }

    public double currentFrame() {
        if (seq == null) return 0;
        double f = (now() - seqStart) * seq.fps;
        return seqLoop ? f : Math.min(f, Math.max(0, seq.numFrames - 1));
    }

    public double effectiveHFov() {
        return scope > 0 && scope <= current.zoom.length ? current.zoom[scope - 1] : CS16Config.get().fov;
    }

    /** Weapon-dependent max speed as a factor of 250 (e.g. AK-47 = 0.884). */
    public double speedFactor() {
        double s = scope > 0 && current.scopedSpeed > 0 ? current.scopedSpeed : current.maxSpeed;
        return s / 250.0;
    }

    private int[] ammo() { return ammo.get(current.id); }

    // ------------------------------------------------------------------ input entry points

    public void selectSlot(int slot) {
        List<CS16Weapon> list = CS16WeaponRegistry.slot(slot);
        if (list.isEmpty()) return;
        CS16Weapon target;
        if (current.slot == slot) target = list.get((list.indexOf(current) + 1) % list.size());
        else target = lastInSlot.getOrDefault(slot, list.get(0));
        switchTo(target);
        menuUntil = now() + 2.5;
    }

    public void quickSwitch() { switchTo(last); menuUntil = now() + 2.5; }

    public void requestReload() { reloadQueued = true; }

    private void switchTo(CS16Weapon w) {
        if (w == current) return;
        last = current;
        current = w;
        lastInSlot.put(w.slot, w);
        scope = 0;
        resumeScope = -1;
        burstLeft = 0;
        reloadQueued = false;
        releaseQueued = false;
        seq = null;          // never keep the previous weapon's animation: its offsets are for a different model file
        needInit = true;
    }

    // ------------------------------------------------------------------ per-frame update

    public void update(Minecraft mc, LocalPlayer p, double dt) {
        double now = now();
        dt = Math.max(0, Math.min(0.1, dt));
        Options o = mc.options;
        boolean alive = !p.isDeadOrDying();
        boolean free = alive && mc.mouseHandler.isMouseGrabbed();
        boolean atk = free && o.keyAttack.isDown();
        boolean sec = free && o.keyUse.isDown();
        boolean atkEdge = atk && !prevAttack, secEdge = sec && !prevSecondary;
        prevAttack = atk;
        prevSecondary = sec;

        recoverPunch(p, dt);
        if (now - lastShot > 0.3) spray = Math.max(0, spray - 8.0 * dt);
        kick = Math.max(0, kick - dt * 7.0);
        inaccuracy = computeInaccuracy(CS16Client.MOVEMENT.controller);

        ModelData m = currentModel();
        if (needInit && m != null) { needInit = false; startDeploy(now); }
        processEvents(now);
        grenades.update(mc, p, dt, now);
        if (!alive) return;

        switch (state) {
            case DEPLOY -> { if (now >= readyAt) { state = State.IDLE; playIdle(); } }
            case RELOAD -> { if (now >= reloadEnd) finishReload(); }
            case SHELL_START -> {
                if (now >= phaseEnd) {
                    state = State.SHELL_INSERT;
                    play(find("", "insert"), false);
                    phaseEnd = now + current.reloadTime;
                }
            }
            case SHELL_INSERT -> {
                int[] a = ammo();
                if (atk && a[0] > 0) { state = State.IDLE; playIdle(); }
                else if (now >= phaseEnd) {
                    a[0]++;
                    if (!CS16Config.get().infiniteAmmo) a[1]--;
                    if (a[0] >= current.clip || (!CS16Config.get().infiniteAmmo && a[1] <= 0)) {
                        Sequence s = find("", "after", "end");
                        state = State.SHELL_END;
                        play(s, false);
                        phaseEnd = now + dur(s, 0.5);
                    } else {
                        play(find("", "insert"), false);
                        phaseEnd = now + current.reloadTime;
                    }
                }
            }
            case SHELL_END -> { if (now >= phaseEnd) { state = State.IDLE; playIdle(); } }
            case ATTACH -> { if (now >= phaseEnd) { silenced = !silenced; state = State.IDLE; playIdle(); } }
            case GRENADE_PULL -> {
                if (!atk) releaseQueued = true;
                if (now >= phaseEnd) {
                    if (releaseQueued) beginThrow(now);
                    else { state = State.GRENADE_HOLD; playIdle(); }
                }
            }
            case GRENADE_HOLD -> { if (!atk) beginThrow(now); }
            case GRENADE_THROW -> {
                if (!thrown && now >= throwAt) { thrown = true; grenades.throwFrom(mc, p, current.grenade); }
                if (now >= phaseEnd) endThrow(now);
            }
            case IDLE -> idle(mc, p, now, atk, atkEdge, sec, secEdge);
        }
    }

    private void idle(Minecraft mc, LocalPlayer p, double now, boolean atk, boolean atkEdge, boolean sec, boolean secEdge) {
        CS16Weapon w = current;
        if (resumeScope >= 0 && now >= nextAttack) { setScope(resumeScope); resumeScope = -1; }
        if (burstLeft > 0 && now >= burstNext) {
            if (ammo()[0] > 0) { shoot(mc, p, now); burstLeft--; burstNext = now + 0.055; } else burstLeft = 0;
        }
        if (reloadQueued) { reloadQueued = false; startReload(now); return; }

        if (w.mode == CS16Weapon.Mode.MELEE) {
            if (now < nextAttack) return;
            if (atk) knife(mc, p, now, false);
            else if (sec) knife(mc, p, now, true);
        } else if (w.mode == CS16Weapon.Mode.GRENADE) {
            int[] a = ammo();
            boolean has = CS16Config.get().infiniteAmmo || a[0] > 0;
            if (atk && has && now >= nextAttack) {
                Sequence s = find("", "pin");
                state = State.GRENADE_PULL;
                play(s, false);
                phaseEnd = now + dur(s, 0.6);
                releaseQueued = false;
            }
        } else {
            if (secEdge && now >= nextAttack) secondary(now);
            boolean burstActive = w.burst && burstMode;
            boolean want = (w.mode == CS16Weapon.Mode.AUTO && !burstActive) ? atk : atkEdge;
            if (want && now >= nextAttack && burstLeft == 0) {
                if (ammo()[0] <= 0) {
                    dryFire(now);
                } else if (burstActive) {
                    shoot(mc, p, now);
                    burstLeft = 2;
                    burstNext = now + 0.055;
                    nextAttack = now + 0.5;
                } else {
                    shoot(mc, p, now);
                }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void shoot(Minecraft mc, LocalPlayer p, double now) {
        CS16Weapon w = current;
        ammo()[0]--;
        playFireSound(w);
        playShootAnim(w);
        muzzleUntil = now + 0.06;
        CS16Ballistics.fire(mc, p, w, inaccuracy);
        if (now - lastAlert > 0.12) { lastAlert = now; CS16Damage.gunshot(mc, p.position(), alertRadius(w)); }

        ThreadLocalRandom r = ThreadLocalRandom.current();
        double pitch = w.recoilPitch * (0.85 + 0.3 * r.nextDouble()) * (1.0 + spray * 0.12);
        applyPunch(p, pitch, (r.nextDouble() - 0.5) * 2.0 * w.recoilYaw);
        spray = Math.min(w.sprayMax, spray + w.perShot);
        lastShot = now;
        kick = 1.0;
        nextAttack = now + w.cycle;
        if (w.bolt && scope > 0) { resumeScope = scope; setScope(0); }
    }

    private void dryFire(double now) {
        CS16SoundManager.INSTANCE.playFirst(CS16Config.get().soundVolume,
                current.pistol ? "weapons/dryfire_pistol.wav" : "weapons/dryfire_rifle.wav");
        Sequence s = find("empty", "shoot");
        if (s != null && s.label.toLowerCase().contains("empty")) play(s, false);
        nextAttack = now + 0.25;
        reloadQueued = true; // CS 1.6 reloads after the empty click
    }

    private void startReload(double now) {
        CS16Weapon w = current;
        if (!w.isGun()) return;
        int[] a = ammo();
        boolean inf = CS16Config.get().infiniteAmmo;
        if (a[0] >= w.clip || (!inf && a[1] <= 0)) return;
        setScope(0);
        resumeScope = -1;
        burstLeft = 0;
        if (w.shell) {
            Sequence s = find("", "start");
            state = State.SHELL_START;
            play(s, false);
            phaseEnd = now + dur(s, 0.55);
        } else {
            state = State.RELOAD;
            play(find("", "reload"), false);
            reloadEnd = now + w.reloadTime;
        }
    }

    private void finishReload() {
        int[] a = ammo();
        boolean inf = CS16Config.get().infiniteAmmo;
        int need = current.clip - a[0];
        int take = inf ? need : Math.min(need, a[1]);
        a[0] += take;
        if (!inf) a[1] -= take;
        state = State.IDLE;
        playIdle();
    }

    private void secondary(double now) {
        CS16Weapon w = current;
        if (w.hasScope()) {
            setScope((scope + 1) % (w.zoom.length + 1));
            CS16SoundManager.INSTANCE.playFirst(CS16Config.get().soundVolume, "weapons/zoom.wav");
            nextAttack = Math.max(nextAttack, now + 0.3);
        } else if (w.silencer) {
            Sequence s = find("", silenced ? "detach" : "attach");
            state = State.ATTACH;
            play(s, false);
            attachStart = now;
            attachDur = dur(s, 2.0);
            phaseEnd = now + attachDur;
        } else if (w.burst) {
            burstMode = !burstMode;
            CS16SoundManager.INSTANCE.playFirst(CS16Config.get().soundVolume, "weapons/dryfire_pistol.wav");
            nextAttack = now + 0.3;
        }
    }

    private void knife(Minecraft mc, LocalPlayer p, double now, boolean stab) {
        Sequence s = stab ? find("", "stab") : pickSlash();
        play(s, false);
        CS16SoundManager sm = CS16SoundManager.INSTANCE;
        double v = CS16Config.get().soundVolume;
        int hit = CS16Ballistics.melee(mc, p, stab ? 0.9 : 1.2, stab ? 65 : current.damage);
        if (hit == 2) {
            sm.playAnyOf(v, "weapons/knife_hit1.wav", "weapons/knife_hit2.wav", "weapons/knife_hit3.wav", "weapons/knife_hit4.wav");
        } else if (hit == 1) {
            sm.playAnyOf(v, "weapons/knife_hitwall1.wav");
        } else {
            sm.playAnyOf(v, "weapons/knife_slash1.wav", "weapons/knife_slash2.wav");
        }
        if (stab && hit != 0) sm.playAnyOf(v, "weapons/knife_stab.wav");
        nextAttack = now + (stab ? 1.1 : 0.42);
    }

    private Sequence pickSlash() {
        ModelData m = currentModel();
        if (m == null) return null;
        int n = 0;
        for (Sequence s : m.sequences) if (s.label.toLowerCase().contains("slash")) n++;
        if (n == 0) return find("", "attack");
        int pick = (altFire = !altFire) ? 0 : n - 1;
        int i = 0;
        for (Sequence s : m.sequences) if (s.label.toLowerCase().contains("slash") && i++ == pick) return s;
        return null;
    }

    private void beginThrow(double now) {
        Sequence s = find("", "throw");
        state = State.GRENADE_THROW;
        play(s, false);
        throwAt = now + Math.min(0.3, dur(s, 0.5) * 0.5);
        phaseEnd = now + dur(s, 0.7);
        thrown = false;
        releaseQueued = false;
    }

    private void endThrow(double now) {
        int[] a = ammo();
        boolean inf = CS16Config.get().infiniteAmmo;
        if (!inf) a[0]--;
        if (!inf && a[0] <= 0) {
            a[0] = 0;
            CS16Weapon fallback = last.slot == 4 ? CS16WeaponRegistry.byId("knife") : last;
            current = fallback;
            lastInSlot.put(fallback.slot, fallback);
            seq = null;      // different model now: drop the grenade animation
            needInit = true;
            return;
        }
        startDeploy(now);
    }

    private void setScope(int level) {
        scope = level;
        if (level == 0) return;
    }

    private void applyPunch(LocalPlayer p, double pitch, double yaw) {
        float oldX = p.getXRot();
        float newX = Mth.clamp(oldX - (float) pitch, -90.0F, 90.0F);
        p.setXRot(newX);
        p.xRotO += newX - oldX;
        p.setYRot(p.getYRot() + (float) yaw);
        p.yRotO += (float) yaw;
        punchPitch += oldX - newX;
        punchYaw += yaw;
    }

    /** The view returns toward where you were aiming, like GoldSrc's punch-angle decay. */
    private void recoverPunch(LocalPlayer p, double dt) {
        double ret = 1.0 - Math.exp(-7.0 * dt);
        double dp = punchPitch * ret, dy = punchYaw * ret;
        if (Math.abs(dp) > 1e-4) {
            punchPitch -= dp;
            float oldX = p.getXRot();
            float newX = Mth.clamp(oldX + (float) dp, -90.0F, 90.0F);
            p.setXRot(newX);
            p.xRotO += newX - oldX;
        }
        if (Math.abs(dy) > 1e-4) {
            punchYaw -= dy;
            p.setYRot(p.getYRot() - (float) dy);
            p.yRotO -= (float) dy;
        }
    }

    private double computeInaccuracy(GoldSrcMovementController c) {
        CS16Weapon w = current;
        if (!w.isGun()) return 0;
        double base = (w.hasScope() && scope == 0 && w.unscopedSpread > 0) ? w.unscopedSpread : w.spreadBase;
        double v = base + spray;
        if (c.ducked) v *= w.crouchMul;
        v += w.spreadMove * Math.min(1.0, c.horizontalSpeed() / 250.0);
        if (!c.grounded) v += w.spreadAir;
        return Math.min(20.0, v);
    }

    // ------------------------------------------------------------------ animation + sound helpers

    private void startDeploy(double now) {
        Sequence s = find("", "draw", "deploy", "holster");   // grenade models call it "deploy"
        if (s == null) s = find("", "idle");
        state = State.DEPLOY;
        play(s, false);
        readyAt = now + Math.min(1.2, dur(s, 0.6));
        nextAttack = Math.max(nextAttack, readyAt);
    }

    private void playIdle() { play(find("", "idle"), true); }

    private void playShootAnim(CS16Weapon w) {
        ModelData m = currentModel();
        if (m == null) return;
        Sequence pick = null;
        if (w.dual) {
            altFire = !altFire;
            pick = find("empty", altFire ? "shoot_right" : "shoot_left", altFire ? "right" : "left");
        }
        if (pick == null) {
            int n = 0;
            for (Sequence s : m.sequences) if (isShoot(s)) n++;
            if (n > 0) {
                int pickIdx = ThreadLocalRandom.current().nextInt(n), i = 0;
                for (Sequence s : m.sequences) if (isShoot(s) && i++ == pickIdx) { pick = s; break; }
            }
        }
        play(pick, false);
    }

    private boolean isShoot(Sequence s) {
        String l = s.label.toLowerCase();
        if (!l.contains("shoot") && !l.contains("fire")) return false;
        if (l.contains("empty")) return false;
        boolean unsil = l.contains("unsil");
        return !current.silencer || unsil == !silenced;
    }

    private void playFireSound(CS16Weapon w) {
        CS16SoundManager sm = CS16SoundManager.INSTANCE;
        double v = CS16Config.get().soundVolume;
        String[] names = w.fire;
        switch (w.id) {
            case "usp" -> names = silenced ? new String[]{"weapons/usp1.wav", "weapons/usp2.wav"} : new String[]{"weapons/usp_unsil-1.wav"};
            case "m4a1" -> names = silenced ? new String[]{"weapons/m4a1-1.wav"} : new String[]{"weapons/m4a1_unsil-1.wav", "weapons/m4a1_unsil-2.wav"};
            case "famas" -> { if (burstMode) names = new String[]{"weapons/famas-burst.wav"}; }
            default -> { }
        }
        if (!sm.playAnyOf(v, names)) sm.playRandomGroup(v, "sound/weapons/" + w.id); // discover by prefix if names differ
    }

    private void play(Sequence s, boolean loop) {
        if (s == null) return;
        seq = s;
        seqLoop = loop;
        seqStart = now();
        lastEventFrame = -1;
    }

    private static double dur(Sequence s, double fallback) {
        return s != null && s.duration() > 0.05 ? s.duration() : fallback;
    }

    /** Finds a sequence whose label contains one of the keys, preferring the right silencer variant. */
    private Sequence find(String exclude, String... keys) {
        ModelData m = currentModel();
        if (m == null) return null;
        boolean wantUnsil = current.silencer && !silenced;
        Sequence fallback = null;
        for (String key : keys) {
            for (Sequence s : m.sequences) {
                String l = s.label.toLowerCase();
                if (!l.contains(key) || (!exclude.isEmpty() && l.contains(exclude))) continue;
                boolean isUnsil = l.contains("unsil") || l.contains("nosil");
                if (!current.silencer || isUnsil == wantUnsil) return s;
                if (fallback == null) fallback = s;
            }
        }
        return fallback;
    }

    private void processEvents(double now) {
        if (seq == null) return;
        double fr = (now - seqStart) * seq.fps;
        if (!seqLoop) fr = Math.min(fr, Math.max(0, seq.numFrames - 1));
        for (Event e : seq.events) {
            if (e.frame > lastEventFrame && e.frame <= fr && e.event == 5004 && !e.options.isEmpty()) {
                String name = e.options.split("\\s+")[0].replace("\"", "");
                CS16SoundManager.INSTANCE.play(name, CS16Config.get().soundVolume);
            }
        }
        lastEventFrame = fr;
    }
}
