# Minecraft in Doom

Real Minecraft (Java Edition 26.3) runs beside a patched GZDoom and is drawn into it. You play Doom's maps as
Steve: Minecraft's hands, hotbar, inventory, blocks, mobs and crafting, on Doom's floors, against Doom's monsters.

- Mine into Doom's walls and floors and walk into the tunnels. What you get depends on the wall: wooden walls give
  oak logs, panelling and crates give planks, brick gives stone bricks, the plating and doors of a base have iron
  in them, marble and the hellish walls are deepslate rich in ore.
- You start with empty hands, as in a new survival world: wood, a pickaxe, stone, iron, and up from there.
- Build with Minecraft's blocks; Doom's monsters and its player are stopped by them.
- Minecraft's mobs and Doom's monsters fight each other. Hearts and armour are Doom's health and armour.
- Movement is Doom's own. `R` switches between Minecraft's hands and Doom's weapons.

Windows only (the two games share the picture through Windows shared memory). Single-player.

## You need

- **Minecraft: Java Edition 26.3**, which you own, with:
  - [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 or newer for 26.3,
  - [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3,
  - Java 25 (the Minecraft launcher brings its own).
- **A Doom game file** of your own: `DOOM.WAD`, `DOOM2.WAD`, `TNT.WAD` or `PLUTONIA.WAD` from your copy of the
  game (Steam and GOG both install them), the shareware `DOOM1.WAD`, or the free
  [Freedoom](https://freedoom.github.io) WADs. None is included.

## Install

1. Unzip this folder anywhere.
2. Have your WAD file somewhere: the `wads` folder here is a handy place, but `Play.cmd` takes one from anywhere.
3. Minecraft: make a separate Fabric installation (profile) for this, and put
   `minecraft-mod\passthrough-0.1.0.jar` and the Fabric API jar into that installation's `mods` folder.
   **Use a separate installation.** With this mod in, Minecraft opens a world of its own called `passthrough` as
   soon as it starts, and makes it afresh every time: a world of yours with that name would be deleted.

## Play

1. Start Minecraft with that installation and wait until it is in its world (an empty one). Leave the window open,
   not minimised; it hides itself when Doom attaches.
2. Double-click `Play.cmd` and pick your WAD in the window that opens (or drag a WAD file onto `Play.cmd`).
   Doom starts at its own title screen: New Game, Load Game and Save Game are Doom's own. `Play.cmd last` starts
   the WAD you played last without asking.

To uninstall, delete the folder and the two jars.

| key | what it does |
|---|---|
| WASD, mouse | move and look, as in Doom; Space jumps |
| left / right mouse | Minecraft attack (mine) / use (place) |
| wheel, 1-9 | hotbar |
| E | inventory (the mouse becomes its cursor; E or Esc closes) |
| Q | drop the held item |
| F | Doom's "use" (doors, switches) |
| R | Minecraft hands / Doom weapons |
| T | Minecraft's chat and commands |
| F1 / F3 / F5 | Minecraft's hide-HUD / debug screen / third person |

Useful console variables (the `~` key): `mc_overscan` (how much wider than the view Minecraft draws, for fast
turns; 1.6), `mc_spawns 0` (no natural mob spawning), `mc_walk 1` (Minecraft moves the player instead of Doom),
`mc_cmd <command>` (run a Minecraft command, e.g. `mc_cmd give @a minecraft:tnt 16`).

To start with a kit instead of empty hands (diamond tools, a bow, blocks, TNT, spawn eggs), start Minecraft with
the Java argument `-Dpassthrough.kit=full` (in the launcher: the installation's "JVM arguments").

## Troubleshooting

- **Doom shows no hotbar or hand:** Minecraft isn't in its world yet, or isn't running. Start it first.
- **Nothing connects:** the two talk over `127.0.0.1:25599`; something else using that port, or a firewall rule
  blocking local connections, stops it.
- **A crash in Doom** writes a stack trace to `run\mc-crash.log`. Minecraft's log is the usual `logs\latest.log`.
- An add-on (a level set or mod: a PWAD or .pk3) can be picked like any WAD; `Play.cmd` asks once which game it is
  for and remembers. What a wall is made of goes by the names of Doom's own textures: walls with textures of an
  add-on's own are plain stone.
- Doom's saves are Doom's. What you built or mined in a map is Minecraft's, and is not in a Doom save: a loaded
  game has the map as Doom saved it, and you keep what you carry.

## Known limits

- Looking through a tunnel into another Doom room shows black: Doom didn't draw that room from where you stand.
- See-through Doom sprites (spectres, fireballs) write no depth, so Minecraft things behind them show through.
- Next to mined cells you can walk through monsters and decorations.
- Coloured sector light and Minecraft light sources aren't carried across.
- With the shareware `DOOM1.WAD`, GZDoom loads nothing beside the game: no add-ons.

## Source and licences

- `gzdoom\` is [GZDoom](https://github.com/ZDoom/gzdoom) 4.14.2 with this project's changes, under the GNU GPL
  v3 (`gzdoom\licenses.zip`). The changes are in `source\`: a patch against GZDoom's commit `99aa489` and the
  files that are new. Apply the patch to that commit and build as GZDoom is built.
- The Minecraft mod is in `source\minecraft-mod\`, under the MIT licence (`LICENSE`). It grew from
  universal-modder's Minecraft x GTA V example (MIT).
- [SkyCraft](https://github.com/chasmlol/SkyCraft) by chasm: ideas for window handling.

Minecraft belongs to Mojang Studios and Microsoft, Doom to id Software. This is a fan project, not affiliated
with or endorsed by either. No game files are included. The code was written with Claude Code (Anthropic's Claude).
