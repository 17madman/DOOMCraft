# DOOMCraft: Minecraft in Doom

Real Minecraft (Java Edition 26.3) runs beside a patched GZDoom 4.14.2 and is drawn into it. You play Doom's maps
as Steve, with Minecraft's hands, hotbar, inventory, blocks, mobs and crafting.

- **Mine Doom's walls and floors.** Wooden walls give logs, panelling gives planks, base plating has iron in it,
  hell's marble is deepslate rich in ore.
- **Survival from the start:** a wooden pickaxe and nothing else. Anything you swing hurts monsters, a fist too.
- **Build** with Minecraft's blocks; monsters are stopped by them.
- **Mobs and monsters fight each other.** Hearts and armour are Doom's health and armour.
- **Saving Minecraft World State:** When making a save in Doom, the MC world's state is saved. When a Doom save is deleted, the corresponding MC world is removed as well.

Windows only, single-player. **No game files are included:** you bring your own Minecraft: Java Edition and your
own Doom WAD (or the free [Freedoom](https://freedoom.github.io)).

## Install

You need Minecraft: Java Edition 26.3 with [Fabric Loader](https://fabricmc.net/use/installer/) and
[Fabric API](https://modrinth.com/mod/fabric-api), and a Doom WAD (`DOOM.WAD`, `DOOM2.WAD`, `TNT.WAD`,
`PLUTONIA.WAD`, the shareware `DOOM1.WAD`, or Freedoom).

1. Download the zip from [Releases](../../releases) and unzip it anywhere.
2. Make a **separate** Fabric installation in the Minecraft launcher and put `minecraft-mod\passthrough-0.1.0.jar`
   and the Fabric API jar into its `mods` folder. (With the mod in, Minecraft opens its own world, `passthrough`,
   at start and makes it afresh each time: a world of yours with that name would be deleted.)

## Play

Double-click `Play.cmd` and pick your WAD (or drag a WAD onto it). `Play.cmd last` replays the last one.

- Minecraft is started for you if you built from source. With your own installation, start it when `Play.cmd` asks;
  Doom follows by itself once Minecraft is in its world.
- Doom starts at its own title screen: New Game, Load and Save are Doom's.
- Quit Doom and Minecraft closes too.
- An add-on (PWAD or .pk3) can be picked like any WAD; you are asked once which game it is for.

| key | what it does |
|---|---|
| WASD, mouse, Space | move, look and jump, as in Doom |
| left / right mouse | mine or attack / place or use |
| wheel, 1-9 | hotbar |
| E, Q, T | inventory, drop, chat and commands |
| F | Doom's "use" (doors, switches) |
| R | Minecraft hands / Doom weapons |
| F1 / F3 / F5 | hide HUD / debug screen / third person |

Settings, in Doom's console (`~`): `mc_spawns 0` (no natural mob spawning), `mc_walk 1` (Minecraft's movement
instead of Doom's), `mc_overscan` (margin for fast turns, 1.6), `mc_cmd <command>` (run a Minecraft command).
For the full kit instead of the wooden pickaxe: `Play.cmd kit` from source, or the JVM argument
`-Dpassthrough.kit=full` on your own installation.

## Known limits

- Saves match: with each Doom save, Minecraft keeps what you carried, built and mined, and puts it back when you
  load that save (and drops it when you delete the save). Minecraft mobs are not saved.
- Walls with an add-on's own textures mine as plain stone.
- Looking through a tunnel into another Doom room shows black.
- Minecraft things show through see-through sprites (spectres, fireballs).
- Next to mined cells you can walk through monsters and decorations.

If Doom shows no hotbar, Minecraft isn't in its world: quit and use `Play.cmd` again. Logs: `run\mc-crash.log`
(Doom), `run\minecraft.log` (Minecraft from source). The two talk over `127.0.0.1:25599`.

## Building from source

Needs Windows 10/11, [Git](https://git-scm.com/download/win),
[Visual Studio 2022](https://visualstudio.microsoft.com/vs/community/) with "Desktop development with C++", and
[JDK 25](https://adoptium.net/temurin/releases/?version=25) (installed, or unpacked as `tools\jdk-25...`).

1. `powershell -ExecutionPolicy Bypass -File tools\gzdoom-changes.ps1 apply` clones GZDoom 4.14.2 into `gzdoom\`
   and puts this project's changes on it.
2. Unzip the official [GZDoom 4.14.2 Windows release](https://github.com/ZDoom/gzdoom/releases/tag/g4.14.2) into
   `tools\gzdoom-release\` (for its sound libraries).
3. `tools\build-gzdoom.cmd` builds it (about 20 minutes; ends with `BUILD OK`).
4. `Play.cmd`. The first run downloads and builds the Minecraft side, a few minutes.

| path | what it is |
|---|---|
| `gzdoom-changes\` | this project's changes to GZDoom: a patch and the new files (`src\mcbridge\`, two shaders, a ZScript) |
| `mc\` | the Fabric mod, "Passthrough" |
| `tools\` | build scripts, the launcher behind `Play.cmd`, `package.ps1` (release zip), test helpers |

After changing GZDoom's source, `tools\gzdoom-changes.ps1 export` writes the changes back to `gzdoom-changes\`.

## How it works

```
GZDoom (patched)                                      Minecraft 26.3 + Fabric mod
  mcbridge.cpp   -- WebSocket 127.0.0.1:25599 -->     camera, map columns, keys, mouse, health
                 <-----------------------------       blocks, mined cells, hits, mobs, explosions
  post-process   <-- shared memory -------------      world colour + depth, hand and HUD
```

- **The map:** each column near the player is sent as floor and ceiling heights and becomes invisible rock in
  Minecraft (32 map units = 1 block). What it is made of comes from the Doom texture's name.
- **Drawing:** a post-process pass puts Minecraft's picture into Doom's wherever it is nearer than Doom's depth,
  lit by the Doom sector; a second pass adds the hand and HUD.
- **Mining:** mined cells are kept in a voxel grid; the shader walks each pixel's ray through it and shows the
  tunnel where Doom's wall was.
- **Combat:** monsters have invisible stand-ins in Minecraft and mobs have them in Doom; damage crosses both ways.

## Credits and licences

- The changes to [GZDoom](https://github.com/ZDoom/gzdoom), and the patched GZDoom in a release, are, like GZDoom,
  under the GNU GPL v3.
- Everything else (the mod, the scripts) is under the MIT licence. Both are set out in [LICENSE](LICENSE).
- The mod grew from universal-modder's Minecraft x GTA V example (MIT).
- [SkyCraft](https://github.com/chasmlol/SkyCraft) by chasm: ideas for window handling.
- [Fabric](https://fabricmc.net).

Minecraft belongs to Mojang Studios and Microsoft, Doom to id Software. This is a fan project, not affiliated with
or endorsed by either. The code was written with Claude Code (Anthropic's Claude).
