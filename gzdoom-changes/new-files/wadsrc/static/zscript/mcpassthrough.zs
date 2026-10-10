// Minecraft passthrough: what the bridge (src/mcbridge) spawns in the map.

// A block built in Minecraft: 32 x 32 x 32, solid to monsters, shots and the player, and not drawn
// (Minecraft's picture of it is composited into the view).
class MCBlockCollider : Actor
{
	Default
	{
		Radius 16;
		Height 32;
		Health 1000000;
		Mass 0x7fffffff;
		RenderStyle "None";
		+SOLID
		+SHOOTABLE
		+NOGRAVITY
		+NOBLOOD
		+NODAMAGE
		+DONTTHRUST
		+NOTAUTOAIMED
		+NEVERTARGET
		+ACTLIKEBRIDGE
		+DONTMORPH
		+NOTELEPORT
	}

	// While Minecraft moves the player (the bridge has him float then: no gravity of Doom's), Minecraft has done
	// his colliding with its blocks already, with its own, narrower player. Doom's is not stopped by them again:
	// it was, half a block out from every wall he built, and so could never get to where Minecraft's stood
	// (he shook there, and the controls went away for a moment each time).
	override bool CanCollideWith(Actor other, bool passive)
	{
		return !(other.player != null && other.bNoGravity);
	}

	States
	{
	Spawn:
		TNT1 A -1;
		Stop;
	}
}

// Where a Minecraft explosion (TNT, creeper, firework) does its damage in Doom.
class MCExplosionSpot : Actor
{
	Default
	{
		+NOBLOCKMAP
		+NOGRAVITY
		+NOINTERACTION
		RenderStyle "None";
	}

	States
	{
	Spawn:
		TNT1 A 2;
		Stop;
	}
}

// A Minecraft mob in Doom's world: not drawn (Minecraft's picture of it is), but monsters and the player's guns
// can hit it. The bridge moves it with the mob, sends what it is hit for to Minecraft and keeps it alive.
class MCMobProxy : Actor
{
	Default
	{
		Radius 10;
		Height 56;
		Health 1000000;
		Mass 0x7fffffff;
		RenderStyle "None";
		+SHOOTABLE
		+NOGRAVITY
		+NOPAIN
		+DONTTHRUST
		+NOTELEPORT
		+DONTMORPH
		+NOTAUTOAIMED
	}

	States
	{
	Spawn:
		TNT1 A -1;
		Stop;
	}
}
