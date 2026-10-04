package com.example.cs16minecraft.weapon;

import com.example.cs16minecraft.weapon.CS16Weapon.Grenade;

import java.util.ArrayList;
import java.util.List;

/**
 * The full CS 1.6 arsenal. Stats are from memory of the 1.6 weapon scripts and are approximations meant to be
 * tuned here; model / sound names are the standard cstrike file names and are resolved against the user's install.
 * Slots: 1 primary, 2 pistol, 3 knife, 4 grenades.
 */
public final class CS16WeaponRegistry {
    private static final List<CS16Weapon> ALL = new ArrayList<>();

    private CS16WeaponRegistry() {}

    private static CS16Weapon w(String id, String name, int slot, String model, String hud) {
        CS16Weapon x = new CS16Weapon(id, name, slot, model, hud);
        ALL.add(x);
        return x;
    }

    static {
        // ---- Pistols
        w("glock18", "Glock 18", 2, "v_glock18", "weapon_glock18").dmg(25).clip(20).cycle(0.2).reload(2.2).range(0.75)
                .acc(0.7, 3, 6, 0.65).spray(0.45, 2.8).recoil(0.9, 0.25).burst().pistol()
                .fire("weapons/glock18-1.wav", "weapons/glock18-2.wav");
        w("usp", "USP .45", 2, "v_usp", "weapon_usp").dmg(34).clip(12).cycle(0.225).reload(2.7).range(0.79)
                .acc(0.45, 2.8, 6, 0.65).spray(0.4, 2.5).recoil(0.9, 0.2).silencer().pistol()
                .fire("weapons/usp1.wav", "weapons/usp2.wav", "weapons/usp_unsil-1.wav");
        w("p228", "P228", 2, "v_p228", "weapon_p228").dmg(32).clip(13).cycle(0.2).reload(2.7).range(0.8)
                .acc(0.55, 3, 6, 0.65).spray(0.4, 2.5).recoil(1.0, 0.25).pistol().fire("weapons/p228-1.wav");
        w("deagle", "Desert Eagle", 2, "v_deagle", "weapon_deagle").dmg(54).clip(7).cycle(0.225).reload(2.2).range(0.81)
                .acc(0.5, 3.5, 7, 0.65).spray(0.8, 4.5).recoil(2.4, 0.4).pistol()
                .fire("weapons/deagle-1.wav", "weapons/deagle-2.wav");
        w("fiveseven", "Five-SeveN", 2, "v_fiveseven", "weapon_fiveseven").dmg(20).clip(20).cycle(0.2).reload(2.7).range(0.885)
                .acc(0.5, 3, 6, 0.65).spray(0.35, 2.3).recoil(0.8, 0.2).pistol().fire("weapons/fiveseven-1.wav");
        w("elite", "Dual Berettas", 2, "v_elite", "weapon_elite").dmg(20).clip(30).cycle(0.12).reload(4.5).range(0.75)
                .acc(0.8, 3.5, 6.5, 0.7).spray(0.4, 3.0).recoil(0.9, 0.3).dual().pistol().fire("weapons/elite_fire.wav");

        // ---- Shotguns
        w("m3", "M3 Super 90", 1, "v_m3", "weapon_m3").dmg(26).pellets(9).clip(8).cycle(0.875).reload(0.5).speed(230).range(0.7)
                .acc(3.0, 1.5, 3, 0.85).spray(0, 0).recoil(3.0, 0.5).shell().fire("weapons/m3-1.wav").flash("muzzleflash3");
        w("xm1014", "XM1014", 1, "v_xm1014", "weapon_xm1014").dmg(20).pellets(6).clip(7).cycle(0.25).reload(0.35).speed(240).range(0.7)
                .acc(2.7, 1.5, 3, 0.85).spray(0, 0).recoil(2.0, 0.4).auto().shell().fire("weapons/xm1014-1.wav").flash("muzzleflash3");

        // ---- SMGs
        w("mac10", "Ingram MAC-10", 1, "v_mac10", "weapon_mac10").dmg(29).clip(30).cycle(0.07).reload(3.15).range(0.82)
                .acc(1.2, 3.5, 7, 0.75).spray(0.25, 4.0).recoil(0.8, 0.45).auto().fire("weapons/mac10-1.wav");
        w("tmp", "Schmidt TMP", 1, "v_tmp", "weapon_tmp").dmg(20).clip(30).cycle(0.07).reload(2.12).range(0.85)
                .acc(1.0, 3.0, 6, 0.75).spray(0.22, 3.6).recoil(0.7, 0.4).auto().fire("weapons/tmp-1.wav", "weapons/tmp-2.wav");
        w("mp5", "MP5 Navy", 1, "v_mp5", "weapon_mp5navy").dmg(26).clip(30).cycle(0.075).reload(2.63).range(0.84)
                .acc(0.9, 3.0, 6, 0.75).spray(0.22, 3.4).recoil(0.75, 0.4).auto().fire("weapons/mp5-1.wav", "weapons/mp5-2.wav");
        w("ump45", "UMP 45", 1, "v_ump45", "weapon_ump45").dmg(30).clip(25).cycle(0.105).reload(3.5).range(0.82)
                .acc(0.9, 3.0, 6, 0.75).spray(0.25, 3.4).recoil(0.9, 0.4).auto().fire("weapons/ump45-1.wav", "weapons/ump45-2.wav");
        w("p90", "FN P90", 1, "v_p90", "weapon_p90").dmg(21).clip(50).cycle(0.07).reload(3.38).speed(245).range(0.885)
                .acc(1.0, 3.5, 7, 0.75).spray(0.2, 3.8).recoil(0.7, 0.4).auto().fire("weapons/p90-1.wav");

        // ---- Rifles
        w("ak47", "AK-47", 1, "v_ak47", "weapon_ak47").dmg(36).clip(30).cycle(0.0955).reload(2.45).speed(221).range(0.98)
                .acc(0.6, 4.5, 8, 0.75).spray(0.35, 3.8).recoil(1.3, 0.5).auto().fire("weapons/ak47-1.wav", "weapons/ak47-2.wav");
        w("m4a1", "Colt M4A1", 1, "v_m4a1", "weapon_m4a1").dmg(33).clip(30).cycle(0.0875).reload(3.05).speed(230).range(0.97)
                .acc(0.5, 4.0, 8, 0.75).spray(0.3, 3.2).recoil(1.0, 0.35).auto().silencer()
                .fire("weapons/m4a1_unsil-1.wav", "weapons/m4a1_unsil-2.wav", "weapons/m4a1-1.wav");
        w("famas", "Clarion 5.56", 1, "v_famas", "weapon_famas").dmg(30).clip(25).cycle(0.0825).reload(3.3).speed(235).range(0.96)
                .acc(0.55, 4.0, 8, 0.75).spray(0.3, 3.2).recoil(1.0, 0.35).auto().burst()
                .fire("weapons/famas-1.wav", "weapons/famas-2.wav", "weapons/famas-burst.wav");
        w("galil", "IMI Galil", 1, "v_galil", "weapon_galil").dmg(30).clip(35).cycle(0.0875).reload(2.45).speed(215).range(0.98)
                .acc(0.6, 4.5, 8, 0.75).spray(0.32, 3.6).recoil(1.1, 0.4).auto().fire("weapons/galil-1.wav", "weapons/galil-2.wav");
        w("aug", "Bullpup", 1, "v_aug", "weapon_aug").dmg(32).clip(30).cycle(0.0825).reload(3.3).speed(240).range(0.96)
                .acc(0.5, 4.0, 8, 0.75).spray(0.28, 3.0).recoil(0.9, 0.3).auto().zoom(55f).fire("weapons/aug-1.wav");
        w("sg552", "Krieg 552", 1, "v_sg552", "weapon_sg552").dmg(33).clip(30).cycle(0.0825).reload(3.0).speed(235).range(0.955)
                .acc(0.55, 4.0, 8, 0.75).spray(0.3, 3.2).recoil(1.0, 0.35).auto().zoom(55f)
                .fire("weapons/sg552-1.wav", "weapons/sg552-2.wav");

        // ---- Snipers (unscoped spread is large, scoped base is tiny)
        w("scout", "Schmidt Scout", 1, "v_scout", "weapon_scout").dmg(75).clip(10).cycle(1.25).reload(2.0).speed(260).scoped(220).range(0.98)
                .acc(0.08, 5.0, 8, 0.8).unscoped(3.5).spray(0, 0).recoil(1.6, 0.2).bolt().zoom(40f, 15f)
                .fire("weapons/scout_fire-1.wav");
        w("awp", "AI Arctic Warfare Magnum", 1, "v_awp", "weapon_awp").dmg(115).clip(10).cycle(1.5).reload(2.5).speed(210).scoped(150).range(0.99)
                .acc(0.05, 6.0, 9, 0.8).unscoped(5.0).spray(0, 0).recoil(2.5, 0.3).bolt().zoom(40f, 10f)
                .fire("weapons/awp1.wav");
        w("g3sg1", "G3/SG-1", 1, "v_g3sg1", "weapon_g3sg1").dmg(80).clip(20).cycle(0.25).reload(3.5).speed(210).scoped(150).range(0.98)
                .acc(0.08, 5.0, 8, 0.8).unscoped(3.5).spray(0.3, 2.0).recoil(1.3, 0.25).zoom(40f, 15f)
                .fire("weapons/g3sg1-1.wav");
        w("sg550", "Krieg 550", 1, "v_sg550", "weapon_sg550").dmg(70).clip(30).cycle(0.25).reload(3.35).speed(210).scoped(150).range(0.98)
                .acc(0.08, 5.0, 8, 0.8).unscoped(3.5).spray(0.3, 2.0).recoil(1.1, 0.25).zoom(40f, 15f)
                .fire("weapons/sg550-1.wav");

        // ---- Machine gun
        w("m249", "M249", 1, "v_m249", "weapon_m249").dmg(32).clip(100).cycle(0.08).reload(4.7).speed(220).range(0.97)
                .acc(1.0, 5.0, 9, 0.8).spray(0.25, 4.5).recoil(1.0, 0.5).auto().fire("weapons/m249-1.wav", "weapons/m249-2.wav");

        // ---- Melee
        w("knife", "Knife", 3, "v_knife", "weapon_knife").melee().speed(250).dmg(15)
                .fire("weapons/knife_slash1.wav", "weapons/knife_slash2.wav");

        // ---- Grenades
        w("hegrenade", "HE Grenade", 4, "v_hegrenade", "weapon_hegrenade").grenade(Grenade.HE).clip(1).reserveMult(0);
        w("flashbang", "Flashbang", 4, "v_flashbang", "weapon_flashbang").grenade(Grenade.FLASH).clip(1).reserveMult(0);
        w("smokegrenade", "Smoke Grenade", 4, "v_smokegrenade", "weapon_smokegrenade").grenade(Grenade.SMOKE).clip(1).reserveMult(0);
    }

    public static List<CS16Weapon> all() { return ALL; }

    public static List<CS16Weapon> slot(int slot) {
        List<CS16Weapon> out = new ArrayList<>();
        for (CS16Weapon w : ALL) if (w.slot == slot) out.add(w);
        return out;
    }

    public static CS16Weapon byId(String id) {
        for (CS16Weapon w : ALL) if (w.id.equals(id)) return w;
        return ALL.get(0);
    }
}
