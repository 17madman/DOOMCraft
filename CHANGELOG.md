# Changelog

## 0.1.0

First release. For Minecraft: Java Edition 26.3 (Fabric) and GZDoom 4.14.2 (included, patched).

- Minecraft's world, hand and HUD drawn into Doom with depth; blocks placed in Doom's maps.
- Mining into Doom's walls and floors, with what a wall is made of taken from its texture.
- Survival start by default, with a wooden pickaxe; the kit is an option.
- Anything Steve swings hurts Doom's monsters, a fist too, and they flinch and are knocked back.
  Damage is set against Doom's own weapons (a diamond sword at full swing is about a pistol shot and a half;
  hurried swings are weak, as in Minecraft); arrows and TNT are scaled to match.
- Minecraft's mobs against Doom's monsters; Doom's health and armour as hearts and armour.
- Doom's own movement by default; Minecraft's as an option (`mc_walk 1`).
- `Play.cmd`: pick any WAD (or drag one onto it); Doom starts at its own menu.
- Doom's saves carry Minecraft's side with them: inventory, what was built and what was mined come back on load.
- Minecraft's world stands still while Doom is paused; mining is no longer five times too slow.
