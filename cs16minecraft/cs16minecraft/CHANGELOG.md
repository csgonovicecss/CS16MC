# Changelog

All notable changes to **CS16 Minecraft** (Fabric, Minecraft 26.2, client-side). The mod loads the player's own
Half-Life / Counter-Strike 1.6 files at runtime and **never ships or downloads Valve assets**.

## [0.7.0]

### Added
- **Bell shots:** shooting a bell rings it (real bell sound and vanilla bell effects). Villagers within 32 blocks stop and
  look at it, cats panic, hostile mobs within 32 blocks are drawn to the sound.
- **Underwater shooting:** weapon fire sounds are low-pass filtered and quieter underwater, and bullets leave a trail of
  bubbles wherever their path is under water.
- **Flashbangs affect mobs:** hostile mobs that can see the detonation lose their target and stumble around for 3-7 s
  (longer when closer). Flashed mobs also ignore gunshot alerts while confused.
- **Doors and trapdoors** can be opened with right click again. Everything else stays blocked, and right click on a door no
  longer triggers a weapon's secondary fire (scope, silencer, burst, stab).
- Debug overlay (F8) now lists the current model's animation sequence names.

### Changed
- **Mob alert movement** no longer uses boosted speeds. Hostile mobs walk to the sound at their normal chase speed,
  villagers flee at their vanilla alerted speed, cats at their vanilla sprint speed, all through real pathfinding and always
  *away from the player*. Fleeing re-paths every ~1 s instead of every tick.
- **Grenades:** after the pin is pulled the animation now freezes on its last frame while the button is held, and the throw
  plays only on release (previously it fell back to the idle animation).
- One-shot animations (shots, slashes, etc.) now return to the idle loop when they finish.

### Fixed
- **Knife slash animation:** the knife now uses any attack animation the model contains (slash / attack / swing / hit, or any
  non-idle, non-draw, non-stab sequence). If a model has none, a procedural swing is shown instead of nothing.
- **Minecraft player damage and fall sounds** (hurt, small/big fall) were still audible at times because the server-side
  player was not silenced. The owner's server player is now kept silent every tick, in addition to the client player.

### Removed
- The G-key radio / voice menu and its key binding.

## [0.6.0]

### Added
- **H** mirrors the viewmodel to the other side of the screen and replays the draw animation (saved in the config).
- **Mob alerts:** every shot alerts hostile mobs within a weapon-dependent radius (about 25 blocks for an AK-47, 54 for an
  AWP, a quarter of that when silenced). Villagers, wandering traders and cats panic for 5 seconds. Grenade explosions alert
  too. Toggle with `mobAlerts`.
- Chat overlay hidden (T and `/` still open the command box) and all toasts / achievement pop-ups blocked.

### Changed
- **Performance:** viewmodel rasteriser rewritten. Triangles are batched, binned into bands and drawn in parallel; each row only
  visits pixels inside the triangle; the per-pixel work uses stepped values and integer bilinear filtering; the GPU texture only
  covers the area the weapon occupies and pixels are copied in bulk. Output is pixel-identical to the previous renderer.
- Damage camera tilt forced off. Menu / death-screen blur forced off.

### Fixed
- **Silencer attach / detach animation:** the silencer sub-model is now chosen by name and switched mid-animation, so it
  appears and disappears with the animation.

## [0.5.0]

### Added
- **Automatic Half-Life / CS 1.6 detection:** Windows registry, default install paths, every drive, every Steam library in
  `libraryfolders.vdf`, app manifests (Half-Life 70, Counter-Strike 10), and any `steamapps/common` folder containing
  `cstrike`. A clear on-screen error lists what was searched when nothing is found. `cs16AssetsPath` is now optional.
- **Environment lighting on viewmodels:** brightness follows world light (torches, lava, glowstone, day, night), smoothed.
- **Block damage:** bullets and the knife wear blocks down. Block hp is hardness x `blockHpPerHardness` (default 150), so each
  weapon chews through blocks at a different rate. Cracks are shown, damage fades after 15 s, bedrock never breaks.
- **HE grenade craters** (radius `craterRadius`, ragged rim, bedrock and obsidian survive).
- Thrown grenades are drawn as their real `w_*.mdl` models, depth-sorted against the viewmodel and hidden behind blocks.
- F5 (third person) disabled.
- CS fall-pain sounds on hard landings.

### Changed
- Viewmodels render at 75% of the window resolution (`viewmodel.renderScale`) with bilinear texture filtering instead of
  stretching a 448 px image.

### Fixed
- Grenade viewmodel glitching right after selecting it (the previous weapon's animation was applied to the grenade's bones
  because its "deploy" sequence was not recognised).

## [0.4.0]

### Added
- **Weapons:** full CS 1.6 arsenal (pistols, shotguns, SMGs, rifles, snipers, M249), knife and HE / flashbang / smoke
  grenades. Per-weapon damage, fire rate, magazine, reload, recoil, spread (standing, moving, jumping, crouched, spray),
  range falloff, headshots, burst and silencer toggles, scopes, shell-by-shell shotgun reloads, `infiniteAmmo`.
- **First-person viewmodels** from the user's `v_*.mdl`: real animations (interpolated), software-rasterised, muzzle flash,
  bob and recoil kick. Minecraft's arm and held item are hidden.
- **Sounds** from the user's install: fire, reload / deploy (from the model's animation events), dry fire, knife, grenades,
  hit sounds, CS footsteps by surface, jump and landing. Minecraft footsteps are muted.
- **HUD** rebuilt from `hud.txt` / `weapon_*.txt` with the original layout formulas: health, armor, money, ammo with icon,
  weapon menu, scope overlay, spread-accurate crosshair, flashbang whiteout, smoke grey-out.
- Interactions disabled (no mining, placing, using items or fist attacks). 1-4 select slots, R reload, Q quick switch.

## [0.3.0]

### Added
- **Crouching** with the CS hulls (0.8 x 1.8 standing, 0.8 x 0.9 crouched, matching eye heights) and CS speed factors.
- **Death:** invisible death screen. The camera drops to the floor and rolls onto one side; the CS death sound plays.
- Minecraft's view bobbing, speed / sprint FOV effects and FOV slider are overridden; `fov` is CS's horizontal FOV.

### Changed
- Ground acceleration is tunable via `groundAccelMultiplier` (default 1.6, about 0.15 s to top speed).

## [0.2.0]

### Fixed
- **Movement:** Minecraft only reports "on ground" when pushed into the floor, so the player flipped between grounded and
  airborne every tick. This caused weak air acceleration, failed jumps and a twitching crosshair.

### Changed
- Physics runs in 5 substeps per tick (100 Hz, like GoldSrc) with interpolated yaw. Jump taps shorter than a tick are caught.
- FOV converted from CS horizontal 4:3 to Minecraft's vertical.

## [0.1.0]

### Added
- Fabric project for Minecraft 26.2 (Java 25, Loom, unobfuscated). Bundled Gradle wrapper bootstrap (no Gradle install needed).
- `config/cs16minecraft.json`, asset manager with background scan and cache outside the game folder.
- Parsers for GoldSrc `.mdl` (geometry, textures, bones, animations, events, attachments), `.spr`, WAD3 and `hud.txt`.
- CS-style HUD and crosshair, GoldSrc movement controller (friction, accelerate, air-accelerate, jump, bhop cap), CS key
  bindings, debug overlay.

## Known limitations
- Damage, block damage, craters and mob behaviour work in **singleplayer / LAN host** only (server-side logic runs through
  the integrated server). A dedicated server would need a server component.
- Scope zoom is limited by Minecraft's minimum FOV (30 degrees vertical).
- No buy menu, armor system or scoreboard yet (money and armor are fixed placeholder values).
- The mod is written against Minecraft 26.2 internals; several mixins are marked optional and are skipped with a log warning
  if a target is renamed in a later version.
