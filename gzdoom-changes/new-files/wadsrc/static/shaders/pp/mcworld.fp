
// Minecraft passthrough: draws Minecraft's world layer into the scene wherever it is nearer than Doom's depth.
// Minecraft's frame was rendered with a slightly older camera, so each pixel's view ray is carried into that
// frame's camera (rotation + translation, refined against Minecraft's depth) before it is sampled.
//
// Mining: Doom can't open its own walls, so where the player has mined into the map the ray is walked through a
// grid of cells (open space / rock / mined) and Doom's surface gives way to Minecraft's picture of the tunnel.

layout(location=0) in vec2 TexCoord;
layout(location=0) out vec4 FragColor;

layout(binding=0) uniform sampler2D SceneTexture;
#if defined(MULTISAMPLE)
layout(binding=1) uniform sampler2DMS SceneDepth;
#else
layout(binding=1) uniform sampler2D SceneDepth;
#endif
layout(binding=2) uniform sampler2D McColor;
layout(binding=3) uniform sampler2D McDepth;
layout(binding=4) uniform sampler2D McGrid;

const float NOTHING = 1.0e8;
const int GRID = 64;
const int CELL_OPEN = 0;
const int CELL_ROCK = 1;
const int CELL_MINED = 2;
const int DOOM = 0;		// Doom's pixel stands
const int ROCK = 1;		// the ray ends on rock at the end of something mined
const int BEYOND = 2;	// the ray leaves something mined into a part of the map Doom didn't draw from here

// Doom's depth along the view axis, in blocks
float doomDepth()
{
	vec2 uv = Offset + TexCoord * Scale;
#if defined(MULTISAMPLE)
	ivec2 ipos = ivec2(max(uv * vec2(textureSize(SceneDepth)), vec2(0.0)));
	float d = texelFetch(SceneDepth, ipos, 0).x;
#else
	ivec2 ipos = ivec2(max(uv * vec2(textureSize(SceneDepth, 0)), vec2(0.0)));
	float d = texelFetch(SceneDepth, ipos, 0).x;
#endif
	return 1.0 / (d * DepthNow.x + DepthNow.y) / DepthNow.z;
}

// Minecraft's depth along its view axis, in blocks (NOTHING where it drew nothing)
float mcDepth(vec2 uv)
{
	float d = texture(McDepth, uv).x;
	float n = DepthMc.x, f = DepthMc.y;
	if (DepthMc.z > 0.5)
	{
		// reversed Z: 1 = near, 0 = far/empty
		if (d <= 0.0) return NOTHING;
		if (DepthMc.w > 0.5) return n * f / (n + d * (f - n));
		float ndc = 2.0 * d - 1.0;
		return 2.0 * n * f / ((f + n) + ndc * (f - n));
	}
	if (d >= 1.0) return NOTHING;
	if (DepthMc.w > 0.5) return n * f / (f - d * (f - n));
	float ndc = 2.0 * d - 1.0;
	return 2.0 * n * f / ((f + n) - ndc * (f - n));
}

// current view space -> the view space Minecraft's frame was rendered in
vec3 toMc(vec3 p)
{
	return vec3(dot(RotRow0.xyz, p) + RotRow0.w, dot(RotRow1.xyz, p) + RotRow1.w, dot(RotRow2.xyz, p) + RotRow2.w);
}

vec3 fromMc(vec3 p)
{
	vec3 q = p - vec3(RotRow0.w, RotRow1.w, RotRow2.w);
	return RotRow0.xyz * q.x + RotRow1.xyz * q.y + RotRow2.xyz * q.z;
}

// -1 outside the grid
int cellType(ivec3 c)
{
	if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(GRID)))) return -1;
	ivec2 texel = ivec2(c.x + (c.y & 7) * GRID, c.z + (c.y >> 3) * GRID);
	return int(texelFetch(McGrid, texel, 0).r * 2.0 + 0.5);
}

// How bright Doom would draw a surface at view depth z (blocks) along this ray: the light level of the sector the
// point is in, then Doom's own fade with distance (the software renderer's light diminishing).
float doomLight(vec3 ray, float z)
{
	float level = Light.z;
	if (Light.y > 0.5)
	{
		// a little towards the eye: the open cell in front of a block's face, not the block
		vec3 dir = vec3(dot(WorldRow0.xyz, ray), dot(WorldRow1.xyz, ray), dot(WorldRow2.xyz, ray));
		ivec3 c = ivec3(floor(CamRel.xyz + dir * max(z - 0.08, 0.0)));
		if (all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, ivec3(GRID))))
		{
			float here = texelFetch(McGrid, ivec2(c.x + (c.y & 7) * GRID, c.z + (c.y >> 3) * GRID), 0).g * 255.0;
			if (here > 0.5) level = here;
		}
	}
	float units = max(z * DepthNow.z, 1.0);
	float vis = min(53.3 / units, 24.0 / 32.0);
	float shade = 2.0 - (level + 12.0) / 128.0;
	return 1.0 - clamp(shade - vis, 0.0, 31.0 / 32.0);
}

// Stone to look at, for a point on a face of the cell grid: sixteen "pixels" a block, each a little lighter or darker.
vec3 stoneAt(vec3 p)
{
	vec3 q = floor(p * 16.0 + 0.01);
	float n = fract(sin(dot(q, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
	float m = fract(sin(dot(floor(q / 3.0), vec3(39.346, 11.135, 83.155))) * 24634.6345);
	return vec3(0.42, 0.42, 0.43) * (0.82 + 0.22 * n + 0.14 * m);
}

// Walks the view ray through the cells. `s` is depth along the view axis, like zDoom.
// (sSkip: where the ray came out of a mined cell while still in front of Doom's surface, or -1)
int march(vec3 ray, float zDoom, out float sHit, out float sSkip)
{
	sSkip = -1.0;
	vec3 dir = vec3(dot(WorldRow0.xyz, ray), dot(WorldRow1.xyz, ray), dot(WorldRow2.xyz, ray));
	vec3 pos = CamRel.xyz;
	ivec3 cell = ivec3(floor(pos));
	vec3 stepDir = sign(dir);
	vec3 tDelta = 1.0 / max(abs(dir), vec3(1.0e-6));
	vec3 tMax = (vec3(cell) + max(stepDir, vec3(0.0)) - pos) * stepDir * tDelta;
	tMax = mix(tMax, vec3(1.0e9), equal(stepDir, vec3(0.0)));

	float s = 0.0;
	float sSurface = 0.0;
	bool pastSurface = false;
	bool surfaceByMined = false;
	bool cameraCarved = cellType(cell) == CELL_MINED;
	bool carved = false;
	sHit = zDoom;
	for (int i = 0; i < 96; i++)
	{
		int type = cellType(cell);
		if (type < 0) break;
		float sExit = min(tMax.x, min(tMax.y, tMax.z));

		if (type == CELL_MINED)
		{
			carved = true;
		}
		else if (carved)
		{
			if (!cameraCarved && s < zDoom - 0.02)
			{
				// Out of the mined cell again before Doom's surface was reached: this ray went through the part of the
				// cell that was open air all along (Doom's walls don't stand on the block grid, so a mined cell is partly
				// in front of the wall it was mined out of). Doom's picture stands here, and the side of the rock
				// Minecraft draws around the hole is not shown out here in front of the wall, where it was a slab of
				// stone standing in the air.
				sSkip = s;
				return DOOM;
			}
			sHit = s;
			if (type == CELL_ROCK) return ROCK;
			// back in the map's own space: Doom drew it only if the camera is in the tunnel looking out
			return (cameraCarved && !pastSurface) ? DOOM : BEYOND;
		}
		else if (!pastSurface)
		{
			if (zDoom <= sExit)
			{
				// Doom's surface is in this cell: it gives way only if what lies just behind it was mined
				pastSurface = true;
				sSurface = zDoom;
				// Is this the piece of wall (or floor) that stands in front of something mined? Doom's walls are not on
				// the block grid: the wall's face is up to half a block in front of the cell that was mined out behind it.
				surfaceByMined = cellType(cell + ivec3(1, 0, 0)) == CELL_MINED || cellType(cell - ivec3(1, 0, 0)) == CELL_MINED
					|| cellType(cell + ivec3(0, 0, 1)) == CELL_MINED || cellType(cell - ivec3(0, 0, 1)) == CELL_MINED
					|| cellType(cell + ivec3(0, 1, 0)) == CELL_MINED || cellType(cell - ivec3(0, 1, 0)) == CELL_MINED;
			}
		}
		else if (type == CELL_ROCK)
		{
			// Through Doom's surface and into rock without meeting a mined cell. Where the surface was the piece in
			// front of a mined cell, the hole is open there all the same, and this is the side of it, seen at a slant:
			// rock. (It was Doom's wall again: an opening looked at from the side closed up.)
			if (!surfaceByMined) return DOOM;
			sHit = s;
			return ROCK;
		}
		else if (s > sSurface + 1.5)
		{
			return DOOM;
		}

		if (tMax.x <= tMax.y && tMax.x <= tMax.z) { s = tMax.x; tMax.x += tDelta.x; cell.x += int(stepDir.x); }
		else if (tMax.y <= tMax.z) { s = tMax.y; tMax.y += tDelta.y; cell.y += int(stepDir.y); }
		else { s = tMax.z; tMax.z += tDelta.z; cell.z += int(stepDir.z); }
	}
	return DOOM;
}

void main()
{
	vec4 scene = texelFetch(SceneTexture, ivec2(gl_FragCoord.xy), 0);
	float zDoom = doomDepth();

	vec2 ndc = TexCoord * 2.0 - 1.0;
	vec3 ray = vec3(ndc * TanFov.xy, -1.0);

	int mode = DOOM;
	float sHit = zDoom;
	float sSkip = -1.0;
	if (CamRel.w > 0.5)
		mode = march(ray, zDoom, sHit, sSkip);

	// walk along the ray to the Minecraft surface seen through this pixel
	float t = min(mode == DOOM ? zDoom : sHit, 256.0);
	vec2 uv = vec2(-1.0);
	float zMc = NOTHING;
	for (int i = 0; i < 4; i++)
	{
		vec3 p = toMc(ray * t);
		if (p.z > -0.01) break;
		uv = (p.xy / -p.z) / TanFov.zw * 0.5 + 0.5;
		// Just past the edge of Minecraft's picture (the view has turned further than the picture's margin allows
		// for): the picture's edge is used, stretched, for a frame or two. A block drawn a little wrong at the side of
		// the screen is not noticed; a block that is gone for a moment is.
		vec2 over = max(-uv, uv - 1.0);
		if (max(over.x, over.y) > 0.2) { zMc = NOTHING; break; }
		uv = clamp(uv, vec2(0.002), vec2(0.998));
		float z = mcDepth(uv);
		if (z >= NOTHING) { zMc = NOTHING; break; }
		vec3 surface = fromMc(p * (z / -p.z));
		zMc = -surface.z;
		t = max(zMc, 0.05);
	}

	vec3 base = scene.rgb;
	float limit = zDoom * 1.002 + 0.02;
	// Seen from outside into something mined, what is behind the mined cells is the map's rock, whatever the grid
	// calls the cell (a cell with a floor part of the way up it counts as open, and Minecraft has no block to draw
	// there): it is rock to look at. Drawn black, as it was, a hole dug at a step or in a floor that isn't on a whole
	// block had black walls.
	bool inside = cellType(ivec3(floor(CamRel.xyz))) == CELL_MINED;
	if (mode == ROCK || (mode == BEYOND && !inside))
	{
		// Minecraft draws the rock's face; where it has none to draw, plain stone
		vec3 dir = vec3(dot(WorldRow0.xyz, ray), dot(WorldRow1.xyz, ray), dot(WorldRow2.xyz, ray));
		base = stoneAt(CamRel.xyz + dir * sHit) * doomLight(ray, sHit);
		limit = sHit + 0.3;
	}
	else if (mode == BEYOND)
	{
		base = vec3(0.02);
		limit = NOTHING * 0.5;
	}

	if (zMc >= NOTHING || zMc > limit || (sSkip > 0.0 && abs(zMc - sSkip) < 0.08))
	{
		FragColor = vec4(base, scene.a);
		return;
	}

	vec4 mc = texture(McColor, uv);
	FragColor = vec4(base * (1.0 - mc.a) + mc.rgb * doomLight(ray, zMc), scene.a);
}
