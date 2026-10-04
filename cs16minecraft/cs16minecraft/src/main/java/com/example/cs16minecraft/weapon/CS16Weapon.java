package com.example.cs16minecraft.weapon;

/** Static definition of one CS 1.6 weapon. Numbers are CS-style (damage out of 100 hp, spread in degrees). */
public final class CS16Weapon {
    public enum Mode { SEMI, AUTO, MELEE, GRENADE }
    public enum Grenade { NONE, HE, FLASH, SMOKE }

    public final String id, name, model, hudFile;
    public final int slot;
    public Mode mode = Mode.SEMI;
    public Grenade grenade = Grenade.NONE;
    public int damage, pellets = 1, clip, reserveMult = 4;
    public double cycle = 0.15, reloadTime = 2.5, maxSpeed = 250, scopedSpeed = -1, rangeMod = 0.98;
    /** Spread cone half-angle in degrees: base + move*speedFraction (+air), crouch multiplier, spray build-up. */
    public double spreadBase = 0.6, spreadMove = 4.0, spreadAir = 8.0, crouchMul = 0.7, perShot = 0.25, sprayMax = 3.0;
    public double unscopedSpread = -1;
    public double recoilPitch = 1.0, recoilYaw = 0.3, headMult = 4.0;
    public float[] zoom = new float[0];
    public boolean silencer, burst, shell, dual, bolt, pistol;
    public String[] fire = new String[0];
    public String flash = "muzzleflash1";

    CS16Weapon(String id, String name, int slot, String model, String hudFile) {
        this.id = id; this.name = name; this.slot = slot; this.model = model; this.hudFile = hudFile;
    }

    CS16Weapon dmg(int v) { damage = v; return this; }
    CS16Weapon pellets(int v) { pellets = v; return this; }
    CS16Weapon clip(int v) { clip = v; return this; }
    CS16Weapon cycle(double v) { cycle = v; return this; }
    CS16Weapon reload(double v) { reloadTime = v; return this; }
    CS16Weapon speed(double v) { maxSpeed = v; return this; }
    CS16Weapon scoped(double v) { scopedSpeed = v; return this; }
    CS16Weapon range(double v) { rangeMod = v; return this; }
    CS16Weapon acc(double base, double move, double air, double crouch) { spreadBase = base; spreadMove = move; spreadAir = air; crouchMul = crouch; return this; }
    CS16Weapon spray(double perShot, double max) { this.perShot = perShot; this.sprayMax = max; return this; }
    CS16Weapon unscoped(double v) { unscopedSpread = v; return this; }
    CS16Weapon recoil(double pitch, double yaw) { recoilPitch = pitch; recoilYaw = yaw; return this; }
    CS16Weapon zoom(float... v) { zoom = v; return this; }
    CS16Weapon auto() { mode = Mode.AUTO; return this; }
    CS16Weapon melee() { mode = Mode.MELEE; return this; }
    CS16Weapon grenade(Grenade g) { mode = Mode.GRENADE; grenade = g; return this; }
    CS16Weapon silencer() { silencer = true; return this; }
    CS16Weapon burst() { burst = true; return this; }
    CS16Weapon shell() { shell = true; return this; }
    CS16Weapon dual() { dual = true; return this; }
    CS16Weapon bolt() { bolt = true; return this; }
    CS16Weapon reserveMult(int v) { reserveMult = v; return this; }
    CS16Weapon pistol() { pistol = true; return this; }
    CS16Weapon flash(String f) { flash = f; return this; }
    CS16Weapon fire(String... f) { fire = f; return this; }

    public boolean hasScope() { return zoom.length > 0; }
    public boolean isGun() { return mode == Mode.SEMI || mode == Mode.AUTO; }
}
