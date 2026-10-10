# DOOMCraft: Minecraft in Doom

Real Minecraft (Java Edition 26.3) runs beside a patched GZDoom 4.14.2 and is drawn into it. You play Doom's maps
as Steve: Minecraft's hands, hotbar, inventory, blocks, mobs and crafting, on Doom's floors, against Doom's monsters.

- Mine into Doom's walls and floors and walk into the tunnels. What you get depends on the wall: wooden walls give
  oak logs, panelling gives planks, the plating of a base has iron in it, hell's marble is deepslate rich in ore.
- You start with empty hands, as in a new survival world: wood, a pickaxe, stone, iron, and up from there.
- Build with Minecraft's blocks; Doom's monsters and its player are stopped by them.
- Minecraft's mobs and Doom's monsters fight each other. Hearts and armour are Doom's health and armour.

Windows only, single-player. **No game files are in this repository**: not Doom's WADs, not GZDoom, not Minecraft.
You bring your own Minecraft: Java Edition and your own Doom WAD (or the free [Freedoom](https://freedoom.github.io)).

## Play it (from a release)

If there is a zip on the [Releases](../../releases) page, that is the quick way: it has the patched GZDoom built and
the mod's jar, and its own README walks through the rest (a Fabric installation of Minecraft 26.3 with the jar in
`mods`, your WAD in `wads`, then `Play.cmd`). The text of that README is [PLAYING.md](PLAYING.md).

## Set it up from this repository

What is here is this project's own code: the changes to GZDoom (`gzdoom-changes\`), the Minecraft mod (`mc\`), and
the scripts. Setting it up takes about half an hour, most of it GZDoom compiling.

**You need**

- Windows 10 or 11, 64-bit, and [Git](https://git-scm.com/download/win).
- [Visual Studio 2022](https://visualstudio.microsoft.com/vs/community/) (Community is fine) with the
  "Desktop development with C++" workload, which brings CMake.
- [JDK 25](https://adoptium.net/temurin/releases/?version=25), either installed, or unpacked into `tools\` so that
  there is a folder `tools\jdk-25...` (the scripts use that one if it is there).
- Minecraft: Java Edition, which you own. The mod is built against 26.3.
- A Doom WAD: `DOOM.WAD`, `DOOM2.WAD`, `TNT.WAD` or `PLUTONIA.WAD` from your copy of the game, the shareware
  `DOOM1.WAD`, or Freedoom.

**Steps** (in a terminal, in this folder)

1. Get GZDoom's source with this project's changes on it:
   ```
   powershell -ExecutionPolicy Bypass -File tools\gzdoom-changes.ps1 apply
   ```
   This clones [GZDoom](https://github.com/ZDoom/gzdoom) into `gzdoom\` at the commit in `gzdoom-changes\COMMIT`
   (4.14.2), applies `gzdoom-changes\gzdoom.patch` and copies `gzdoom-changes\new-files` over it.
2. Get the official GZDoom 4.14.2 Windows release (the zip on
   [GZDoom's releases page](https://github.com/ZDoom/gzdoom/releases/tag/g4.14.2)) and unzip it into
   `tools\gzdoom-release\`. Only its sound libraries and licence texts are used (`openal32.dll`, `sndfile.dll`,
   `soundfonts`, `fm_banks`, `licenses.zip`): the build copies them beside the game it builds.
3. Build GZDoom:
   ```
   tools\build-gzdoom.cmd
   ```
   It ends with `BUILD OK` and `gzdoom\build\RelWithDebInfo\gzdoom.exe`.
4. Have your WAD file somewhere. A folder `wads\` here is a handy place (Git ignores it), but any place will do.
5. Start Minecraft with the mod:
   ```
   run-minecraft.cmd
   ```
   The first time, this downloads Gradle, Fabric and Minecraft 26.3 and builds the mod (a few minutes). It starts
   a development copy of Minecraft (its own folder, `mc\run`; an offline player; nothing of your launcher is
   touched) which opens an empty world by itself and waits. Leave the window open, not minimised.
6. Start Doom:
   ```
   Play.cmd
   ```
   A file window opens: pick your WAD (anywhere on the disk; it need not be in `wads\`). Doom starts at its own
   title screen, so New Game, Load Game and Save Game are Doom's. Dragging a WAD onto `Play.cmd` does the same.
   An add-on WAD asks once which game it is for. Minecraft's window hides itself when Doom attaches.

To use the mod in your own Minecraft launcher instead of the development copy: `tools\build-mc.cmd build` makes
`mc\build\libs\passthrough-0.1.0.jar`; see [PLAYING.md](PLAYING.md) for where it goes.

**Other scripts**

- `Play.cmd last` starts the WAD played last without asking; `Play.cmd <path to a WAD>` starts that one.
- `run-minecraft.cmd kit` starts with a kit (diamond tools, bow, blocks, TNT, spawn eggs) instead of empty hands.
- `tools\gzdoom-changes.ps1 export` writes the changes in `gzdoom\` back to `gzdoom-changes\` (before committing).
- `tools\package.ps1` builds a release zip into `dist\`.
- `tools\go.ps1` and `tools\t.py` run the two games in the background and drive Doom by console commands, for testing.

## Controls (in the Doom window)

| key | what it does |
|---|---|
| WASD, mouse | move and look; Shift sprints, Space jumps |
| left / right mouse | Minecraft attack (mine) / use (place) |
| wheel, 1-9 | hotbar |
| E | inventory (the mouse becomes its cursor; E or Esc closes) |
| Q | drop the held item |
| F | Doom's "use" (doors, switches) |
| R | Minecraft hands / Doom weapons |
| T | Minecraft's chat (the keyboard types into it; Enter sends, Esc cancels) |
| F1 / F3 / F5 | Minecraft's hide-HUD / debug screen / third person (Doom's chase camera) |

Console variables: `mc_walk` (Minecraft moves the player), `mc_spawns` (natural mob spawning), `mc_companion`,
`mc_steve_move` (Minecraft's feel when Doom moves him),
`mc_overscan` (how much wider than the view Minecraft draws, for fast turns; 1.6), `mc_predict` (how far ahead of a turning
view it draws, seconds; 0.045),
`mc_viewheight`, `mc_cursor_speed`, `mc_radius`, `mc_layers` (debug: 1 world, 2 overlay). `mc_cmd <command>` runs a
Minecraft command.

## How it works

```
GZDoom (gzdoom\, patched)                               Minecraft 26.3 + Fabric (mc\)
  src\mcbridge\mcbridge.cpp  -- WebSocket 127.0.0.1:25599 -->  camera / map columns / keys / mouse / health
                             <------------------------------  explosions, blocks, mined cells, hits, mobs
  hw_postprocess (PPMinecraft)  <-- shared memory ------------  world colour + depth, hand/HUD overlay
  shaders\pp\mcworld.fp, mcoverlay.fp
```

- **Coordinates:** 32 map units = 1 block. Doom (x, y, z) -> Minecraft (x / 32, z / 32 + 64, -y / 32).
- **The map in Minecraft:** every column near the player is sent as floor and ceiling heights (in eighths of a
  block) and becomes invisible, stone-hard rock, with partial blocks where a floor isn't on a whole block.
- **Drawing:** two post-process passes. The world pass tests Minecraft's depth against Doom's scene depth and
  lights each pixel by its Doom sector and distance; the overlay pass adds the hand and HUD.
- **What the map is made of:** by the name of Doom's texture there (`textureMaterial` in `mcbridge.cpp`). Wooden
  walls (WOOD*, the wooden doors) are oak logs, panelling and crates are planks, brick is stone bricks, the plating,
  doors and computers of a base are stone with iron in every third block, marble and the hellish walls are
  deepslate rich in ore, grass and brown ground are grass and dirt, anything else stone with the odd ore. A floor is
  its texture on top and earth and stone below.
  You start with empty hands; `run-minecraft.cmd kit` starts with a kit (diamond tools, blocks, TNT).
- **Mining:** Doom can't open its own walls, so mined cells are kept in a small voxel grid; the world pass walks
  each pixel's ray through it and shows Minecraft's tunnel where Doom's wall was, and the bridge moves the player
  itself next to mined cells.
- **Movement:** Doom's own, as in Doom (the default). Minecraft's player follows Doom's. Two switches, both off
  by default: `mc_walk 1` has Minecraft move the player instead (its walking, jumping, sneaking and collision, on
  the map's columns as blocks), and `mc_steve_move 1` keeps Doom moving him but with Minecraft's feel.
- **Combat:** Doom's monsters have invisible stand-ins in Minecraft that mobs hunt; Minecraft's mobs have invisible
  shootable stand-ins in Doom that monsters fight. Damage crosses both ways. Hearts and armour are Doom's.

## Layout

| path | what it is |
|---|---|
| `gzdoom-changes\` | what this project changes in GZDoom: `gzdoom.patch` (hooks in a handful of engine files) and `new-files\` (`src\mcbridge\`, two shaders, `zscript\mcpassthrough.zs`) |
| `gzdoom\` | not in the repository: GZDoom's source with the changes applied (step 1) |
| `mc\` | the Fabric mod, "Passthrough" |
| `PLAYING.md` | the player's guide: it is the README of a release zip |
| `tools\` | the build scripts, the launcher behind `Play.cmd`, packaging and test helpers (`mcframe.py` reads Minecraft's exported frames, `mc-command.py` sends it a command); and, not in the repository, what you put there (JDK, the GZDoom release) and Gradle's cache |
| `run\`, `wads\`, `dist\` | not in the repository: GZDoom's settings and logs, your WADs, built releases |

## Status

Confirmed by the player: blocks and mobs draw in Doom with depth; mining into walls and floors and walking into the
tunnels; mobs and monsters fighting each other; survival with Doom's health; inventory with the mouse; monster
loot and XP; the companion with its converted model.

Built but not confirmed since the last changes: Minecraft-side movement (`mc_walk`), chat and the function keys; the companion's smooth movement, mobs kept outside monsters,
Minecraft's movement model, the lower spawn rate, the wider item pickup.

Known limits:
- Looking through a tunnel into another Doom room shows black: Doom didn't draw that room from where you stand.
- See-through Doom sprites (spectres, fireballs) write no depth, so Minecraft things behind them show through.
- Next to mined cells you can walk through monsters and decorations (Doom's own collision is off there).
- Coloured sector light and Minecraft light sources aren't carried across.
- A crash writes a stack trace to `run\mc-crash.log`.

## Credits and licences

- The changes to [GZDoom](https://github.com/ZDoom/gzdoom) (`gzdoom-changes\`) are, like GZDoom, under the GNU GPL v3.
- Everything else here (the mod, the scripts) is under the MIT licence: see [LICENSE](LICENSE).
- The mod grew from universal-modder's Minecraft x GTA V example (MIT).
- [SkyCraft](https://github.com/chasmlol/SkyCraft) by chasm: ideas for window handling.
- [Fabric](https://fabricmc.net).

Minecraft belongs to Mojang Studios and Microsoft, Doom to id Software. This is a fan project, not affiliated with
or endorsed by either. I've also used Claude Code Sonnet 5.5 for much of the code, and manually reviewed it. Honestly though most of this README is AI slop (im a lazy bum)
