// Minecraft passthrough: see mcbridge.h.

// rapidjson must be configured exactly as in serializer.cpp: its templates are instantiated in both files, and with
// different settings the linker merges two layouts of the same types (the save/load menu then crashes).
#define RAPIDJSON_48BITPOINTER_OPTIMIZATION 0
#define RAPIDJSON_HAS_CXX11_RVALUE_REFS 1
#define RAPIDJSON_HAS_CXX11_RANGE_FOR 1
#define RAPIDJSON_PARSE_DEFAULT_FLAGS kParseFullPrecisionFlag

#include <cmath>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "mcbridge.h"
#include "mcshm.h"
#include "ws.h"

#include "rapidjson/document.h"

#include "actor.h"
#include "c_cvars.h"
#include "c_dispatch.h"
#include "c_console.h"
#include "d_event.h"
#include "d_player.h"
#include "d_protocol.h"
#include "doomstat.h"
#include "g_levellocals.h"
#include "gamestate.h"
#include "i_interface.h"
#include "keydef.h"
#include "menustate.h"
#include "p_local.h"
#include "p_trace.h"
#include "printf.h"
#include "r_sky.h"
#include "r_utility.h"
#include "texturemanager.h"

CVAR(Bool, mc_passthrough, true, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
CVAR(Int, mc_port, 25599, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// true: the mouse and the number keys are Minecraft's (hand, hotbar); false: Doom's weapons
CVAR(Bool, mc_hands, true, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// how far around the player the map is handed to Minecraft as collision, in blocks
CVAR(Int, mc_radius, 24, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
CVAR(Bool, mc_debug, false, 0)
// Minecraft's movement instead of Doom's: see steveMove. Off: Doom's own.
CVAR(Bool, mc_steve_move, false, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// Minecraft moves the player: its own walking, jumping, sneaking, sprinting and collision, on the map's columns as
// blocks; Doom's player is put where Minecraft's stands. Off: Doom moves him (with mc_steve_move's feel).
CVAR(Bool, mc_walk, false, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// Minecraft renders this much more than the view shows, each way. Its frame is a frame or two old when it is
// drawn; on a quick turn the new edge of the view was never in it, and what should be there was missing until
// the next frame caught up. With a margin it is there. (1 = none; more costs sharpness.)
CVAR(Float, mc_overscan, 1.6f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// How far ahead (seconds) of a turning view Minecraft is asked to draw.
CVAR(Float, mc_predict, 0.045f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// Minecraft's hostile mobs spawn by themselves on the map's floors
CVAR(Bool, mc_spawns, true, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// the companion (class MCPurin, from the companion folder): spawned beside the player, brought back when it is lost
CVAR(Bool, mc_companion, true, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// how fast the cursor moves on Minecraft's screens
CVAR(Float, mc_cursor_speed, 1.f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// eye height in map units while Minecraft is attached: Steve's eyes are 1.62 blocks up (Doom's marine: 41 units)
CVAR(Float, mc_viewheight, 52.f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
EXTERN_CVAR(Int, screenblocks)
// which of Minecraft's layers are drawn: 1 = world, 2 = hand/HUD overlay
CVAR(Int, mc_layers, 3, 0)

namespace
{
	const double UNITS = 32.0;			// map units per block
	const int Y_OFFSET = 64;			// Minecraft y of Doom z = 0
	const int EIGHTHS_NONE = 32767;		// "no ceiling" (sky) in a column
	const int COLUMNS_PER_TIC = 120;
	const double PI = 3.14159265358979323846;

	WsClient link;
	bool started;
	int generation = -1;
	long long frameCounter;

	// the camera the 3D view is being drawn with, in Minecraft coordinates
	struct Pose
	{
		double pos[3];
		float yaw, pitch;
		float tanX, tanY;
		float light;
		bool valid;
	};
	Pose now;
	int viewWidth, viewHeight;
	bool viewSent;

	// one column of the map as Minecraft has it: solid from `lo` up to `floor8 / 8`, and from `ceil8 / 8` up to `hi`
	struct Column
	{
		short floor8, ceil8, lo, hi;
		bool operator==(const Column &o) const { return floor8 == o.floor8 && ceil8 == o.ceil8 && lo == o.lo && hi == o.hi; }
	};
	std::unordered_map<long long, Column> sentColumns;
	// blocks built in Minecraft that have a collider actor in the map (by position: actor pointers don't outlive a map)
	std::unordered_set<long long> blockActors;
	struct Flying
	{
		DVector3 pos;
		int seen;
	};
	std::unordered_map<int, Flying> projectiles;
	FLevelLocals *currentLevel;
	int currentMapTime = -1;
	FString currentMap;
	int tic;
	bool heldAttack, heldUse, heldPick;

	// a Minecraft screen (inventory, crafting table...) is open: the mouse is its cursor
	bool screenOpen;
	double cursorX, cursorY;
	bool cursorMoved;
	bool heldButton[3];
	bool shiftDown;
	int sentHealth = -1, sentArmor = -1;
	bool sentPeds;

	// mining
	const int GRID_XZ = 64, GRID_Y = 64;
	const double TUNNEL_HALF = 12.0, TUNNEL_HEIGHT = 54.0;	// the player's box in a tunnel: it fits a 1 x 2 block passage
	std::unordered_set<long long> mined;
	std::unordered_map<long long, uint8_t> columnLight;	// the light level of the sector each column is in (0: outside the map)
	int bandLo = Y_OFFSET - 16, bandHi = Y_OFFSET + 24;
	std::vector<uint8_t> grid;
	int gridOrigin[3];
	uint64_t gridSerial;
	bool gridDirty = true, gridUsed;
	bool tunnelling, tunnelGround;
	DVector3 tunnelPos;
	double tunnelVelZ;

	// Minecraft moves the player (mc_walk)
	bool walking;
	int walkSince, walkHold, walkBlocked;
	bool walkFloats;
	bool creativeCheats;	// Minecraft is in creative: Doom's player has "god" and "notarget" from this bridge
	bool pendingNew;		// a new game has been started: Minecraft has not been told yet
	FString pendingLoad;	// a saved game has just been loaded: its name, until Minecraft has been told
	FString walkBlocker;	// what last stopped Doom's player going where Minecraft's stood (for mc_state)
	double mcPos[3], mcVel[3];
	uint64_t mcPosNanos;
	bool mcGround;
	int mcPosTic = -100;
	bool moveKeys[7];
	// Minecraft's own keys: F1 hides its HUD; T opens its chat, and the keyboard then types into it
	bool typing, typingOpened;
	int typingSince;

	long long key(int x, int z) { return (static_cast<long long>(x) << 32) ^ static_cast<unsigned int>(z); }
	long long key3(int x, int y, int z) { return (static_cast<long long>(x & 0xFFFFF) << 40) | (static_cast<long long>(y & 0xFFFFF) << 20) | (z & 0xFFFFF); }

	double mcX(double x) { return x / UNITS; }
	double mcY(double z) { return z / UNITS + Y_OFFSET; }
	double mcZ(double y) { return -y / UNITS; }
	DVector3 toDoom(double x, double y, double z) { return DVector3(x * UNITS, -z * UNITS, (y - Y_OFFSET) * UNITS); }

	bool inGame()
	{
		return mc_passthrough && gamestate == GS_LEVEL && !demoplayback && primaryLevel != nullptr
			&& players[consoleplayer].mo != nullptr;
	}

	void send(const FString &message)
	{
		link.send(std::string(message.GetChars(), message.Len()));
	}

	void sendKey(const char *name, bool down)
	{
		FString m;
		m.Format("{\"t\":\"key\",\"k\":\"%s\",\"down\":%s}", name, down ? "true" : "false");
		send(m);
	}

	void sendCommand(const char *command)
	{
		FString c = command;
		c.Substitute("\\", "\\\\");
		c.Substitute("\"", "\\\"");
		FString m;
		m.Format("{\"t\":\"cmd\",\"c\":\"%s\"}", c.GetChars());
		send(m);
	}

	// Minecraft's hearts and hotbar are the HUD while the hands are Minecraft's; Doom's own (ammo) with its weapons
	bool hudHidden;	// F1: Minecraft's HUD and hand are hidden
	int hudSaid = -1, hudSaidTic;

	// One HUD and one pair of hands at a time: Minecraft's with its hands out, Doom's with Doom's weapons. (With
	// Doom's weapons Minecraft's hand and hotbar were drawn too, over the gun and the status bar.)
	void chooseHud()
	{
		const int want = mc_hands ? 12 : 11;
		if (screenblocks != want)
			screenblocks = want;
		const int hidden = hudHidden || !mc_hands ? 1 : 0;
		if (hidden != hudSaid || tic - hudSaidTic > 105)	// (said again now and then: Minecraft may have come later)
		{
			hudSaid = hidden;
			hudSaidTic = tic;
			send(hidden ? "{\"t\":\"hud\",\"hidden\":true}" : "{\"t\":\"hud\",\"hidden\":false}");
		}
	}

	void releaseKeys()
	{
		if (heldAttack) sendKey("attack", false);
		if (heldUse) sendKey("use", false);
		if (heldPick) sendKey("pick", false);
		heldAttack = heldUse = heldPick = false;
		for (int b = 0; b < 3; b++)
		{
			if (heldButton[b])
			{
				FString m;
				m.Format("{\"t\":\"click\",\"b\":%d,\"down\":false}", b);
				send(m);
				heldButton[b] = false;
			}
		}
	}

	//==========================================================================
	//
	// The map as columns of blocks
	//
	//==========================================================================

	// The sector at a point, or null in the void outside the map (the BSP gives every point a subsector;
	// only inside its polygon is the point really in it).
	sector_t *sectorAt(FLevelLocals *Level, double x, double y)
	{
		subsector_t *sub = Level->PointInRenderSubsector(DVector2(x, y));
		if (sub == nullptr || sub->numlines < 3)
			return nullptr;
		for (uint32_t i = 0; i < sub->numlines; i++)
		{
			const seg_t &seg = sub->firstline[i];
			const double x1 = seg.v1->fX(), y1 = seg.v1->fY();
			const double dx = seg.v2->fX() - x1, dy = seg.v2->fY() - y1;
			// the subsector lies on the right of each of its segs
			if ((y - y1) * dx - (x - x1) * dy > 0.5 * sqrt(dx * dx + dy * dy))
				return nullptr;
		}
		return sub->sector;
	}

	struct Cell
	{
		bool inside;
		double floor, ceiling;
		bool sky;
		int light;
		int material;
	};

	// What a Doom texture is made of, as one of Minecraft's materials (the mod's Materials: what a cell of the map is
	// when it is mined, and so what it drops). By the texture's name: wood is wood, the plating of a base has iron in
	// it, brick is brick, the brown and green ground outdoors is dirt and grass, marble is deepslate with more ore.
	enum { M_STONE = 0, M_DIRT = 1, M_GRASS = 2, M_SAND = 3, M_WOOD = 4, M_PLANKS = 5, M_BRICKS = 6, M_GRAVEL = 10,
		M_METAL = 14, M_MARBLE = 15, M_SOLID = 0x100 };

	int textureMaterial(FTextureID id)
	{
		FGameTexture *texture = id.isValid() ? TexMan.GetGameTexture(id, true) : nullptr;
		if (texture == nullptr)
			return M_STONE;
		FString name = texture->GetName();
		name.ToUpper();
		// (switches are named for what they are set in: SW1WOOD, SW2BRIK)
		if (name.Len() > 3 && (name.IndexOf("SW1") == 0 || name.IndexOf("SW2") == 0))
			name = name.Mid(3);
		auto has = [&](const char *part) { return name.IndexOf(part) >= 0; };
		auto is = [&](const char *prefix) { return name.IndexOf(prefix) == 0; };
		if (is("WOODMET") || is("WOODGAR") || is("WOODVERT"))
			return M_PLANKS;
		if (is("WOOD") || is("BIGDOOR5") || is("BIGDOOR6") || is("BIGDOOR7"))
			return M_WOOD;
		if (is("PANEL") || is("PANBO") || is("PANCAS") || is("PANRED") || is("CRAT") || is("CEIL1_1") || is("CEIL1_3") || is("FLAT5_1")
			|| is("FLAT5_2"))
			return M_PLANKS;
		if (has("BRICK") || has("BRIK") || is("BRWINDOW") || is("BRNSMAL") || is("ZZWOLF") || is("ZELDOOR") || is("STONE2") || is("STONE3")
			|| is("FLOOR7_1") || is("BRNBIGC") || is("MODWALL"))
			return M_BRICKS;
		if (is("GRASS") || is("RROCK19") || is("RROCK20") || is("MFLR8_3"))
			return M_GRASS;
		if (is("FLAT10") || is("RROCK16") || is("RROCK17") || is("RROCK18") || is("MFLR8_4") || is("FLAT1_1") || is("FLAT1_2") || is("BROWNHUG")
			|| is("ASHWALL") || is("TANROCK5") || is("ZIMMER") || is("FLOOR6_") || is("FLAT5_7") || is("FLAT5_8") || is("SLIME13"))
			return M_DIRT;
		if (is("RROCK03") || is("RROCK04") || is("FLAT5_6") || is("FLOOR5_4"))
			return M_GRAVEL;
		if (is("MARB") || is("GSTONE") || is("GSTVINE") || is("GSTFONT") || is("GSTGARG") || is("GSTLION") || is("GSTSATYR") || is("DEM1_")
			|| is("FLOOR7_2") || is("SP_DUDE") || is("SP_FACE") || is("SP_HOT") || is("SKIN") || is("SK_") || is("REDWALL") || is("FIRE")
			|| is("CRACKLE") || is("ROCKRED") || is("SFLR"))
			return M_MARBLE;
		if (has("METAL") || is("SUPPORT") || is("PIPE") || is("SHAWN") || is("SILVER") || is("STAR") || is("COMP") || is("TEK") || is("DOOR")
			|| is("BIGDOOR") || is("SPACEW") || is("PLAT") || is("LITE") || is("ICKWALL") || is("GRAY") || is("EXIT") || is("NUKE")
			|| is("BRONZE") || is("BROWN") || is("CEMENT") || is("SPCDOOR") || is("TLITE") || is("CEIL") || is("FLOOR") || is("FLAT")
			|| is("STEP") || is("GATE") || is("CONS") || is("SLAD") || is("MIDB") || is("BLAKWAL") || is("MFLR8_1") || is("MFLR8_2"))
			return M_METAL;
		return M_STONE;
	}

	// For a column outside the map (solid: the inside of a wall): what the nearest wall is faced with.
	int wallMaterial(FLevelLocals *Level, int bx, int bz)
	{
		const double x = (bx + 0.5) * UNITS, y = -(bz + 0.5) * UNITS;
		double best = 3.0 * UNITS * 3.0 * UNITS;	// (further in than that it is plain rock)
		const line_t *nearest = nullptr;
		for (const line_t &line : Level->lines)
		{
			const double x1 = line.v1->fX(), y1 = line.v1->fY(), dx = line.v2->fX() - x1, dy = line.v2->fY() - y1;
			const double length2 = dx * dx + dy * dy;
			const double t = length2 > 0.0 ? clamp(((x - x1) * dx + (y - y1) * dy) / length2, 0.0, 1.0) : 0.0;
			const double ex = x - (x1 + dx * t), ey = y - (y1 + dy * t);
			const double d = ex * ex + ey * ey;
			if (d < best)
			{
				best = d;
				nearest = &line;
			}
		}
		if (nearest == nullptr)
			return M_STONE | M_SOLID;
		for (int s = 0; s < 2; s++)
		{
			const side_t *side = nearest->sidedef[s];
			if (side == nullptr)
				continue;
			for (int part : {side_t::mid, side_t::top, side_t::bottom})
			{
				const FTextureID id = side->GetTexture(part);
				if (id.isValid() && !id.isNull())
					return textureMaterial(id) | M_SOLID;
			}
		}
		return M_STONE | M_SOLID;
	}

	Cell sampleCell(FLevelLocals *Level, int bx, int bz)
	{
		static const double offsets[5][2] = {{0, 0}, {-10, -10}, {10, -10}, {-10, 10}, {10, 10}};
		const double cx = (bx + 0.5) * UNITS, cy = -(bz + 0.5) * UNITS;
		Cell cell = {};
		for (const auto &o : offsets)
		{
			const double x = cx + o[0], y = cy + o[1];
			sector_t *sector = sectorAt(Level, x, y);
			if (sector != nullptr)
			{
				cell.inside = true;
				cell.floor = sector->floorplane.ZatPoint(x, y);
				cell.ceiling = sector->ceilingplane.ZatPoint(x, y);
				cell.sky = sector->GetTexture(sector_t::ceiling) == skyflatnum;
				cell.light = clamp<int>(sector->lightlevel, 1, 255);
				cell.material = textureMaterial(sector->GetTexture(sector_t::floor));
				break;
			}
		}
		return cell;
	}

	// The vertical band of the map that exists in Minecraft: some rock below the lowest floor to dig into, a little
	// above the highest ceiling, at most GRID_Y blocks.
	void findBand(FLevelLocals *Level)
	{
		double low = 1e9, high = -1e9;
		for (auto &sector : Level->sectors)
		{
			low = min(low, sector.floorplane.ZatPoint(sector.centerspot));
			high = max(high, sector.ceilingplane.ZatPoint(sector.centerspot));
		}
		if (low > high)
			low = high = 0;
		bandLo = clamp(static_cast<int>(floor(low / UNITS)) + Y_OFFSET - 8, -60, 250);
		bandHi = clamp(static_cast<int>(ceil(high / UNITS)) + Y_OFFSET + 4, bandLo + 8, bandLo + GRID_Y);
	}

	// Every column near the player goes to Minecraft: the map's floors and ceilings, and solid rock outside it.
	void sendColumns(FLevelLocals *Level, AActor *player)
	{
		const int radius = clamp<int>(mc_radius, 4, 30);
		const int px = static_cast<int>(floor(player->X() / UNITS)), pz = static_cast<int>(floor(-player->Y() / UNITS));
		FString message, materials;
		int count = 0;
		// nearest columns first
		for (int ring = 0; ring <= radius && count < COLUMNS_PER_TIC; ring++)
		{
			for (int dz = -ring; dz <= ring && count < COLUMNS_PER_TIC; dz++)
			{
				const int step = (dz == -ring || dz == ring) ? 1 : max(2 * ring, 1);
				for (int dx = -ring; dx <= ring && count < COLUMNS_PER_TIC; dx += step)
				{
					const int x = px + dx, z = pz + dz;
					const Cell cell = sampleCell(Level, x, z);
					Column column;
					column.lo = static_cast<short>(bandLo);
					column.hi = static_cast<short>(bandHi);
					if (!cell.inside)
					{
						column.floor8 = static_cast<short>(bandHi * 8);
						column.ceil8 = EIGHTHS_NONE;
					}
					else
					{
						column.floor8 = static_cast<short>(clamp<int>(lround(cell.floor / (UNITS / 8)) + Y_OFFSET * 8, bandLo * 8, bandHi * 8));
						column.ceil8 = cell.sky ? EIGHTHS_NONE : static_cast<short>(lround(cell.ceiling / (UNITS / 8)) + Y_OFFSET * 8);
					}

					const long long k = key(x, z);
					uint8_t &light = columnLight[k];
					if (light != cell.light)
					{
						// lights flicker and switch: only the compositor's grid needs to know
						light = static_cast<uint8_t>(cell.light);
						gridDirty = true;
					}
					auto sent = sentColumns.find(k);
					if (sent != sentColumns.end() && sent->second == column)
						continue;
					sentColumns[k] = column;
					gridDirty = true;
					message.AppendFormat("%s%d,%d,%d,%d,%d,%d", count == 0 ? "{\"t\":\"cols\",\"c\":[" : ",",
						x, z, column.floor8, column.ceil8, column.lo, column.hi);
					materials.AppendFormat("%s%d,%d,%d", count == 0 ? "{\"t\":\"mats\",\"m\":[" : ",", x, z,
						cell.inside ? cell.material : wallMaterial(Level, x, z));
					count++;
				}
			}
		}

		if (count > 0)
		{
			// (what the columns are made of first: it is asked for when the columns are laid)
			materials += "]}";
			send(materials);
			message += "]}";
			send(message);
		}
	}

	//==========================================================================
	//
	// Mining: the cells of the map as a grid the compositor and the player's movement read
	//
	//==========================================================================

	enum { CELL_OPEN = 0, CELL_ROCK = 1, CELL_MINED = 2 };

	int cellType(int x, int y, int z)
	{
		if (mined.count(key3(x, y, z)) != 0)
			return CELL_MINED;
		auto found = sentColumns.find(key(x, z));
		if (found == sentColumns.end())
			return CELL_ROCK;
		const Column &c = found->second;
		if (y * 8 < (c.floor8 & ~7))
			return CELL_ROCK;
		if (c.ceil8 != EIGHTHS_NONE && (c.ceil8 <= c.floor8 || y >= ((c.ceil8 + 7) >> 3)))
			return CELL_ROCK;
		return CELL_OPEN;
	}

	bool minedNear(const DVector3 &feet, double reach)
	{
		if (mined.empty())
			return false;
		const double x = mcX(feet.X), y = mcY(feet.Z), z = mcZ(feet.Y);
		for (int cy = static_cast<int>(floor(y - 1.0)); cy <= static_cast<int>(floor(y + 2.8)); cy++)
			for (int cz = static_cast<int>(floor(z - reach)); cz <= static_cast<int>(floor(z + reach)); cz++)
				for (int cx = static_cast<int>(floor(x - reach)); cx <= static_cast<int>(floor(x + reach)); cx++)
					if (mined.count(key3(cx, cy, cz)) != 0)
						return true;
		return false;
	}

	// The grid as an RGBA8 atlas: GRID_XZ x GRID_XZ cells per height, 8 x 8 heights. Red = cell type * 127,
	// green = the light level there (0 = unknown).
	void buildGrid(AActor *player)
	{
		const int ox = ((static_cast<int>(floor(player->X() / UNITS)) >> 3) << 3) - GRID_XZ / 2;
		const int oz = ((static_cast<int>(floor(-player->Y() / UNITS)) >> 3) << 3) - GRID_XZ / 2;
		gridUsed = true;
		if (!gridDirty && !grid.empty() && ox == gridOrigin[0] && oz == gridOrigin[2] && bandLo == gridOrigin[1])
			return;
		gridDirty = false;
		gridOrigin[0] = ox;
		gridOrigin[1] = bandLo;
		gridOrigin[2] = oz;
		const int side = GRID_XZ * 8;
		grid.resize(static_cast<size_t>(side) * side * 4);
		const bool anyMined = !mined.empty();
		for (int z = 0; z < GRID_XZ; z++)
		{
			for (int x = 0; x < GRID_XZ; x++)
			{
				const long long k = key(ox + x, oz + z);
				auto column = sentColumns.find(k);
				auto lit = columnLight.find(k);
				const int light = lit != columnLight.end() ? lit->second : 0;
				for (int y = 0; y < GRID_Y; y++)
				{
					const int wy = min(y + bandLo, bandHi - 1);
					int type = CELL_ROCK;
					if (anyMined && mined.count(key3(ox + x, wy, oz + z)) != 0)
					{
						type = CELL_MINED;
					}
					else if (column != sentColumns.end())
					{
						const Column &c = column->second;
						if (wy * 8 >= (c.floor8 & ~7) && (c.ceil8 == EIGHTHS_NONE || (c.ceil8 > c.floor8 && wy < ((c.ceil8 + 7) >> 3))))
							type = CELL_OPEN;
					}
					uint8_t *texel = &grid[((static_cast<size_t>((y >> 3) * GRID_XZ + z) * side) + (y & 7) * GRID_XZ + x) * 4];
					texel[0] = static_cast<uint8_t>(type * 127);
					// a tunnel has no sector: what was mined is lit like a torch-lit mine
					texel[1] = static_cast<uint8_t>(type == CELL_MINED ? 150 : light);
					texel[2] = 0;
					texel[3] = 255;
				}
			}
		}
		gridSerial++;
	}

	//==========================================================================
	//
	// Mining: moving through what was mined. Doom's own collision stops at its walls, so next to mined cells the
	// player is moved here instead: no clipping against the map, and a box against the cells and the map's real
	// floors and ceilings.
	//
	//==========================================================================

	// What a point of Minecraft's damage is in Doom's. Set against Doom's own weapons (a pistol shot is about 10, a
	// shotgun blast about 70, a punch about 11) and its monsters (a zombieman has 20, an imp 60, a demon 150): a fist
	// is 3, a wooden pickaxe 6, a stone sword 15, an iron one 18, a diamond one 21; a zombie's blow 9. A good sword,
	// swung as fast as it will go at full strength, does about what the pistol does and has to be at arm's length to
	// do it; Doom's own guns stay the better weapons, as they should in Doom.
	const double MC_TO_DOOM = 3.0;
	const int ARROW_DAMAGE = 24;				// a bow's arrow (about 8 in Minecraft): two for a zombieman, three an imp
	const double BLAST = 0.5;					// a Minecraft explosion against a rocket's of the same reach (TNT: 64 at its middle)
	int lastSwingTic = -1000;

	// Whether a point (Doom coordinates) is inside something solid.
	bool solidAt(FLevelLocals *Level, double dx, double dy, double dz)
	{
		const int x = static_cast<int>(floor(mcX(dx))), y = static_cast<int>(floor(mcY(dz))), z = static_cast<int>(floor(mcZ(dy)));
		const long long k = key3(x, y, z);
		if (blockActors.count(k) != 0)
			return true;
		if (mined.count(k) != 0)
			return false;
		sector_t *sector = sectorAt(Level, dx, dy);
		if (sector == nullptr)
			return true;
		if (dz < sector->floorplane.ZatPoint(dx, dy))
			return true;
		return sector->GetTexture(sector_t::ceiling) != skyflatnum && dz > sector->ceilingplane.ZatPoint(dx, dy);
	}

	bool boxBlocked(FLevelLocals *Level, const DVector3 &feet)
	{
		static const double heights[4] = {0.5, 18.0, 36.0, TUNNEL_HEIGHT};
		for (double h : heights)
			for (int i = 0; i < 4; i++)
				if (solidAt(Level, feet.X + ((i & 1) ? TUNNEL_HALF : -TUNNEL_HALF), feet.Y + ((i & 2) ? TUNNEL_HALF : -TUNNEL_HALF), feet.Z + h))
					return true;
		return false;
	}

	void leaveTunnel(player_t *player)
	{
		if (!tunnelling)
			return;
		tunnelling = false;
		player->cheats &= ~(CF_NOCLIP | CF_NOCLIP2);
		if (player->mo != nullptr)
		{
			player->mo->flags &= ~MF_NOGRAVITY;
			player->mo->flags2 &= ~MF2_FLY;
		}
	}

	void moveInTunnel(FLevelLocals *Level, player_t *player)
	{
		AActor *mo = player->mo;
		if (mo == nullptr || player->health <= 0 || !minedNear(tunnelling ? tunnelPos : mo->Pos(), 1.6))
		{
			leaveTunnel(player);
			return;
		}

		if (!tunnelling)
		{
			tunnelling = true;
			tunnelGround = true;
			tunnelPos = mo->Pos();
			tunnelVelZ = 0;
		}
		player->cheats |= CF_NOCLIP | CF_NOCLIP2;

		// where Doom's (unclipped) movement took the player this tic, one axis at a time
		DVector3 pos = tunnelPos;
		const DVector3 want = mo->Pos();
		for (int axis = 0; axis < 2; axis++)
		{
			DVector3 next = pos;
			if (axis == 0) next.X = want.X; else next.Y = want.Y;
			if (!boxBlocked(Level, next))
			{
				pos = next;
				continue;
			}
			// a step up (Doom's 24 units), if there is room
			for (double up = 8.0; tunnelGround && up <= 24.0; up += 8.0)
			{
				DVector3 stepped = next;
				stepped.Z += up;
				if (!boxBlocked(Level, stepped))
				{
					pos = stepped;
					break;
				}
			}
		}

		if (tunnelGround && (player->cmd.ucmd.buttons & BT_JUMP))
			tunnelVelZ = mc_steve_move ? 7.62 : 9.0;
		tunnelVelZ = max(tunnelVelZ - (mc_steve_move ? 0.726 : 1.0), -30.0);
		DVector3 next = pos;
		next.Z += tunnelVelZ;
		tunnelGround = false;
		if (!boxBlocked(Level, next))
		{
			pos = next;
		}
		else
		{
			// as far as it goes
			double open = 0, blocked = tunnelVelZ;
			for (int i = 0; i < 8; i++)
			{
				const double middle = (open + blocked) / 2;
				DVector3 probe = pos;
				probe.Z += middle;
				if (boxBlocked(Level, probe)) blocked = middle; else open = middle;
			}
			pos.Z += open;
			tunnelGround = tunnelVelZ < 0;
			tunnelVelZ = 0;
		}

		tunnelPos = pos;
		mo->SetOrigin(pos, true);
		mo->Vel.Z = 0;
		mo->floorz = pos.Z;
		player->viewz = pos.Z + player->viewheight;
		player->onground = tunnelGround;
	}

	//==========================================================================
	//
	// What Minecraft does to Doom
	//
	//==========================================================================

	bool isBlockActor(AActor *actor)
	{
		static PClassActor *type;
		if (type == nullptr)
			type = PClass::FindActor("MCBlockCollider");
		return type != nullptr && actor != nullptr && actor->IsKindOf(type);
	}

	// Blocks built in Minecraft become solid, invisible actors: monsters, shots and the player stop at them.
	void setBlock(FLevelLocals *Level, int x, int y, int z, bool solid)
	{
		const long long k = key3(x, y, z);
		const DVector3 pos = toDoom(x + 0.5, y, z + 0.5);
		if (solid && blockActors.insert(k).second)
		{
			PClassActor *type = PClass::FindActor("MCBlockCollider");
			if (type != nullptr)
				Spawn(Level, type, pos, NO_REPLACE);
		}
		else if (!solid && blockActors.erase(k) != 0)
		{
			auto it = Level->GetThinkerIterator<AActor>(FName("MCBlockCollider"));
			while (AActor *actor = it.Next())
			{
				if (fabs(actor->X() - pos.X) < 1 && fabs(actor->Y() - pos.Y) < 1 && fabs(actor->Z() - pos.Z) < 1)
				{
					actor->Destroy();
					break;
				}
			}
		}
	}

	void removeBlockActors(FLevelLocals *Level)
	{
		auto it = Level->GetThinkerIterator<AActor>(FName("MCBlockCollider"));
		while (AActor *actor = it.Next())
			actor->Destroy();
		blockActors.clear();
		auto proxies = Level->GetThinkerIterator<AActor>(FName("MCMobProxy"));
		while (AActor *actor = proxies.Next())
			actor->Destroy();
	}

	void explode(FLevelLocals *Level, AActor *player, const DVector3 &pos, double radiusBlocks)
	{
		PClassActor *type = PClass::FindActor("MCExplosionSpot");
		if (type == nullptr)
			return;
		AActor *spot = Spawn(Level, type, pos, NO_REPLACE);
		// TNT (radius 4) reaches as far as a rocket's blast (128 units) and does half the damage
		const int reach = static_cast<int>(radiusBlocks * UNITS);
		P_RadiusAttack(spot, player, static_cast<int>(reach * BLAST), reach, NAME_None, RADF_HURTSOURCE);
	}

	// A monster hit by something of Minecraft's shows it, every time: it flinches (its pain state, which Doom itself
	// only gives it now and then, by the monster's "pain chance") and is knocked back a little, less the heavier it
	// is, as a mob in Minecraft is. Without this a punch or an arrow often did nothing to see.
	void flinch(AActor *actor, const DVector3 &dir)
	{
		if (actor == nullptr || actor->health <= 0 || actor->player != nullptr || isBlockActor(actor))
			return;
		const double push = clamp(500.0 / max(actor->Mass, 1), 0.4, 5.0);
		actor->Vel.X += dir.X * push;
		actor->Vel.Y += dir.Y * push;
		FState *pain = actor->FindState(NAME_Pain);
		if (pain != nullptr && !(actor->flags & MF_SKULLFLY))
			actor->SetState(pain);
	}

	// A swing of what Steve holds: it hits the first thing of Doom's within three blocks along his view, for the
	// damage Minecraft gives the item (a fist 1, a wooden pickaxe 2, a diamond sword 7), in Doom's measure. As in
	// Minecraft, a swing made before the last one has been recovered from is weak: clicking as fast as the mouse
	// will go does a fifth of the damage a swing, not more of it. (`perSecond`: the item's full swings a second;
	// a sword 1.6, a pickaxe 1.2, a fist 4.)
	void swing(FLevelLocals *Level, AActor *player, double minecraftDamage, double perSecond)
	{
		const double recovery = TICRATE / clamp(perSecond, 0.5, 4.0);
		const double ready = clamp((tic - lastSwingTic) / recovery, 0.0, 1.0);
		lastSwingTic = tic;
		minecraftDamage *= 0.2 + 0.8 * ready * ready;
		const DAngle yaw = player->Angles.Yaw, pitch = player->Angles.Pitch;
		const DVector3 dir(yaw.Cos() * pitch.Cos(), yaw.Sin() * pitch.Cos(), -pitch.Sin());
		const DVector3 from = player->PosPlusZ(player->player != nullptr ? player->player->viewheight : 41.0);
		sector_t *sector = sectorAt(Level, from.X, from.Y);
		if (sector == nullptr)
			return;
		FTraceResults hit;
		if (!Trace(from, sector, dir, 3.0 * UNITS, MF_SHOOTABLE, ML_BLOCKEVERYTHING, player, hit, TRACE_NoSky))
			return;
		if (hit.HitType != TRACE_HitActor || hit.Actor == nullptr || isBlockActor(hit.Actor))
			return;
		// (a Minecraft mob's stand-in: Minecraft has hit the mob itself)
		if (hit.Actor->GetClass()->TypeName == FName("MCMobProxy"))
			return;
		// A critical hit, as in Minecraft: a full-strength swing made while falling (on the way down from a jump) does
		// half as much again, with Minecraft's sparks and sound where it lands.
		const bool critical = ready > 0.9 && player->player != nullptr && !player->player->onground && player->Vel.Z < 0.0;
		if (critical)
		{
			minecraftDamage *= 1.5;
			const DVector3 at = hit.Actor->PosPlusZ(hit.Actor->Height * 0.6);
			FString c;
			c.Format("particle minecraft:crit %.2f %.2f %.2f 0.3 0.4 0.3 0.5 14 force", mcX(at.X), mcY(at.Z), mcZ(at.Y));
			sendCommand(c.GetChars());
			c.Format("playsound minecraft:entity.player.attack.crit player @a %.2f %.2f %.2f 1 1", mcX(at.X), mcY(at.Z), mcZ(at.Y));
			sendCommand(c.GetChars());
		}
		P_DamageMobj(hit.Actor, player, player, max(1, static_cast<int>(minecraftDamage * MC_TO_DOOM + 0.5)), NAME_Melee, 0, dir.Angle());
		// (a tap flinches nothing: that would hold a monster helpless under a fast mouse)
		if (ready > 0.6)
			flinch(hit.Actor, dir);
	}

	void traceProjectile(FLevelLocals *Level, AActor *player, int id, const char *kind, const DVector3 &pos)
	{
		auto found = projectiles.find(id);
		if (found == projectiles.end())
		{
			projectiles[id] = {pos, tic};
			return;
		}

		const DVector3 from = found->second.pos;
		found->second = {pos, tic};
		DVector3 dir = pos - from;
		const double distance = dir.Length();
		if (distance < 1.0)
			return;
		dir /= distance;
		sector_t *sector = sectorAt(Level, from.X, from.Y);
		if (sector == nullptr)
			return;

		FTraceResults hit;
		if (!Trace(from, sector, dir, distance, MF_SHOOTABLE, ML_BLOCKEVERYTHING, player, hit, TRACE_NoSky))
			return;
		if (hit.HitType == TRACE_HitActor && isBlockActor(hit.Actor))
			return;	// Minecraft's own block: it stops the arrow itself

		const bool firework = strcmp(kind, "firework") == 0;
		bool stick = true;
		if (hit.HitType == TRACE_HitActor && hit.Actor != nullptr)
		{
			stick = false;
			if (!firework)
			{
				P_DamageMobj(hit.Actor, nullptr, player, ARROW_DAMAGE, NAME_None, 0, dir.Angle());
				flinch(hit.Actor, dir);
			}
		}
		if (firework)
			explode(Level, player, hit.HitPos - dir * 4.0, 3.0);

		FString m;
		m.Format("{\"t\":\"projhit\",\"id\":%d,\"pos\":[%.3f,%.3f,%.3f],\"stick\":%s}", id,
			mcX(hit.HitPos.X), mcY(hit.HitPos.Z), mcZ(hit.HitPos.Y), stick ? "true" : "false");
		send(m);
		projectiles.erase(id);
	}

	void updateProxies(FLevelLocals *Level, const rapidjson::Value &list);
	void mobHit(int handle, double damage, const DVector3 &from);

	void handle(FLevelLocals *Level, AActor *player, const std::string &text)
	{
		rapidjson::Document m;
		m.Parse(text.c_str());
		if (m.HasParseError() || !m.IsObject() || !m.HasMember("t") || !m["t"].IsString())
			return;
		const char *t = m["t"].GetString();

		if (strcmp(t, "explosion") == 0)
		{
			const auto &p = m["pos"];
			explode(Level, player, toDoom(p[0].GetDouble(), p[1].GetDouble(), p[2].GetDouble()), m["r"].GetDouble());
		}
		else if (strcmp(t, "blocks") == 0)
		{
			const auto &set = m["set"];
			for (rapidjson::SizeType i = 0; i + 2 < set.Size(); i += 3)
				setBlock(Level, set[i].GetInt(), set[i + 1].GetInt(), set[i + 2].GetInt(), true);
			const auto &clear = m["clear"];
			for (rapidjson::SizeType i = 0; i + 2 < clear.Size(); i += 3)
				setBlock(Level, clear[i].GetInt(), clear[i + 1].GetInt(), clear[i + 2].GetInt(), false);
		}
		else if (strcmp(t, "mcpos") == 0)
		{
			// where Minecraft's player is, how fast he moves and when that was true
			if (m.HasMember("pos") && m["pos"].IsArray() && m["pos"].Size() >= 3)
			{
				for (rapidjson::SizeType i = 0; i < 3; i++)
				{
					mcPos[i] = m["pos"][i].GetDouble();
					mcVel[i] = m.HasMember("vel") && m["vel"].IsArray() && m["vel"].Size() >= 3 ? m["vel"][i].GetDouble() : 0.0;
				}
				mcPosNanos = m.HasMember("tn") && m["tn"].IsUint64() ? m["tn"].GetUint64() : 0;
				mcGround = m.HasMember("ground") && m["ground"].IsBool() && m["ground"].GetBool();
				mcPosTic = tic;
			}
		}
		else if (strcmp(t, "mined") == 0)
		{
			const auto &set = m["set"];
			for (rapidjson::SizeType i = 0; i + 2 < set.Size(); i += 3)
				mined.insert(key3(set[i].GetInt(), set[i + 1].GetInt(), set[i + 2].GetInt()));
			gridDirty = true;
		}
		else if (strcmp(t, "swing") == 0)
		{
			// a swing of whatever he holds, a bare fist too, with its Minecraft damage ("d")
			swing(Level, player, m.HasMember("d") && m["d"].IsNumber() ? m["d"].GetDouble() : 1.0,
				m.HasMember("s") && m["s"].IsNumber() ? m["s"].GetDouble() : 4.0);
		}
		else if (strcmp(t, "proj") == 0)
		{
			const auto &list = m["p"];
			for (rapidjson::SizeType i = 0; i < list.Size(); i++)
			{
				const auto &e = list[i];
				traceProjectile(Level, player, e[0].GetInt(), e[1].GetString(),
					toDoom(e[2].GetDouble(), e[3].GetDouble(), e[4].GetDouble()));
			}
		}
		else if (strcmp(t, "mode") == 0)
		{
			// Minecraft's game mode: in creative Doom's player cannot be hurt and its monsters take no notice of him
			// (Doom's own "god" and "notarget"); back in survival both are off again, if it was this that put them on.
			const bool creative = m.HasMember("creative") && m["creative"].IsBool() && m["creative"].GetBool();
			player_t *p = &players[consoleplayer];
			if (creative && !creativeCheats)
			{
				creativeCheats = true;
				p->cheats |= CF_GODMODE | CF_NOTARGET;
				// (those already after him forget him)
				auto it = Level->GetThinkerIterator<AActor>();
				while (AActor *actor = it.Next())
				{
					if ((actor->flags3 & MF3_ISMONSTER) && actor->target == player)
						actor->target = nullptr;
				}
			}
			else if (creative)
			{
				p->cheats |= CF_GODMODE | CF_NOTARGET;	// (a new map, a loaded game: put on again)
			}
			else if (creativeCheats)
			{
				creativeCheats = false;
				p->cheats &= ~(CF_GODMODE | CF_NOTARGET);
			}
		}
		else if (strcmp(t, "screen") == 0)
		{
			const bool open = m["open"].GetBool();
			if (open && !screenOpen)
			{
				releaseKeys();
				cursorX = viewWidth / 2.0;
				cursorY = viewHeight / 2.0;
				cursorMoved = true;
			}
			else if (!open && screenOpen)
			{
				releaseKeys();
			}
			screenOpen = open;
		}
		else if (strcmp(t, "hurt") == 0)
		{
			// a mob hit Steve: it is the Doom player's health
			if (player->health > 0)
				P_DamageMobj(player, nullptr, nullptr, max(1, static_cast<int>(m["d"].GetDouble())), NAME_None);
		}
		else if (strcmp(t, "heal") == 0)
		{
			player_t *p = player->player;
			if (p != nullptr && p->health > 0)
			{
				const int gained = max(1, static_cast<int>(m["d"].GetDouble()));
				p->health = player->health = min(p->health + gained, max(p->health, 100));
			}
		}
		else if (strcmp(t, "mobs") == 0)
		{
			updateProxies(Level, m["m"]);
		}
		else if (strcmp(t, "mobhit") == 0)
		{
			const auto &from = m["from"];
			mobHit(m["h"].GetInt(), m["d"].GetDouble(), toDoom(from[0].GetDouble(), from[1].GetDouble(), from[2].GetDouble()));
		}
		else if (strcmp(t, "hello") == 0)
		{
			Printf("Minecraft passthrough connected\n");
		}
	}

	//==========================================================================
	//
	// Minecraft's mobs vs Doom's monsters
	//
	// Doom's monsters go to Minecraft as "peds": each gets an invisible stand-in there that hostile mobs hunt, and a
	// mob's hit on it comes back as "mobhit". Minecraft's mobs come here as "mobs": each gets an invisible,
	// shootable MCMobProxy that monsters fight, and what it is hit for goes back as "mobdmg".
	//
	//==========================================================================

	const double MOB_RANGE = 40 * UNITS;		// monsters this near the player take part
	const double MOB_SIGHT = 24 * UNITS;		// a monster picks a fight with a mob it sees this near
	const int PROXY_HEALTH = 1000000;
	const double DOOM_TO_MC = 1.0 / 3.0;		// a zombieman's shot (3-15) is 1-5 of a mob's 20 health


	std::unordered_map<AActor *, int> monsterHandles;	// keys are only compared, never followed
	int nextHandle = 1;
	std::vector<std::pair<int, AActor *>> monstersNow;	// this tic's live monsters near the player
	std::vector<AActor *> proxiesNow;					// this tic's mob proxies (special1 = Minecraft entity id)
	std::unordered_map<int, int> proxySeen;				// Minecraft entity id -> tic it was last listed

	void collectFighters(FLevelLocals *Level, AActor *player)
	{
		monstersNow.clear();
		proxiesNow.clear();
		std::unordered_map<AActor *, int> handles;
		PClassActor *proxyType = PClass::FindActor("MCMobProxy");
		auto it = Level->GetThinkerIterator<AActor>();
		while (AActor *actor = it.Next())
		{
			if (proxyType != nullptr && actor->IsKindOf(proxyType))
			{
				proxiesNow.push_back(actor);
			}
			else if ((actor->flags3 & MF3_ISMONSTER) && actor->health <= 0 && monsterHandles.count(actor) != 0)
			{
				// it was alive last tic: like a Minecraft mob, it leaves experience and something to pick up
				static const char *const loot[] = {"rotten_flesh", "bone", "gunpowder", "arrow", "iron_nugget", "leather", "string", "coal"};
				const int handle = monsterHandles[actor];
				const DVector3 pos = actor->Pos();
				FString c;
				c.Format("summon minecraft:experience_orb %.2f %.2f %.2f {Value:%d}", mcX(pos.X), mcY(pos.Z) + 0.5, mcZ(pos.Y),
					clamp(actor->SpawnHealth() / 10, 2, 60));
				sendCommand(c.GetChars());
				c.Format("summon minecraft:item %.2f %.2f %.2f {Item:{id:\"minecraft:%s\",count:%d}}", mcX(pos.X), mcY(pos.Z) + 0.5, mcZ(pos.Y),
					loot[handle % 8], 1 + handle % 2);
				sendCommand(c.GetChars());
				// The former humans (zombieman, shotgun guy, chaingunner, the SS) leave wood about every other time: a
				// map with no wooden walls in it still has a crafting table and a first pickaxe's worth to be found.
				static const char *const formerHumans[] = {"ZombieMan", "ShotgunGuy", "ChaingunGuy", "WolfensteinSS"};
				bool human = false;
				for (const char *name : formerHumans)
				{
					PClassActor *type = PClass::FindActor(name);
					human = human || (type != nullptr && actor->IsKindOf(type));
				}
				if (human && ((static_cast<unsigned>(handle) * 2654435761u) >> 16) % 100 < 50)
				{
					c.Format("summon minecraft:item %.2f %.2f %.2f {Item:{id:\"minecraft:oak_log\",count:%d}}", mcX(pos.X), mcY(pos.Z) + 0.5,
						mcZ(pos.Y), 1 + (handle >> 1) % 2);
					sendCommand(c.GetChars());
				}
			}
			else if ((actor->flags3 & MF3_ISMONSTER) && actor->health > 0 && (actor->flags & MF_SHOOTABLE)
				&& !(actor->flags & MF_FRIENDLY) && actor->Distance2D(player) < MOB_RANGE)
			{
				auto known = monsterHandles.find(actor);
				const int handle = known != monsterHandles.end() ? known->second : nextHandle++;
				handles[actor] = handle;
				monstersNow.emplace_back(handle, actor);
			}
		}
		monsterHandles.swap(handles);
	}

	AActor *proxyFor(int id)
	{
		for (AActor *proxy : proxiesNow)
			if (proxy->special1 == id)
				return proxy;
		return nullptr;
	}

	// {"t":"mobs","m":[[id,"zombie",x,y,z],...]}: every fighting mob near the player
	void updateProxies(FLevelLocals *Level, const rapidjson::Value &list)
	{
		PClassActor *type = PClass::FindActor("MCMobProxy");
		if (type == nullptr)
			return;
		for (rapidjson::SizeType i = 0; i < list.Size(); i++)
		{
			const auto &e = list[i];
			const int id = e[0].GetInt();
			const DVector3 pos = toDoom(e[2].GetDouble(), e[3].GetDouble(), e[4].GetDouble());
			AActor *proxy = proxyFor(id);
			if (proxy == nullptr)
			{
				proxy = Spawn(Level, type, pos, NO_REPLACE);
				proxy->special1 = id;
				proxy->health = PROXY_HEALTH;
				proxiesNow.push_back(proxy);
			}
			else
			{
				proxy->SetOrigin(pos, true);
			}
			proxySeen[id] = tic;
		}
	}

	// {"t":"mobhit","h":handle,"d":damage,"from":[x,y,z],"k":"zombie"}: a mob hit a monster's stand-in
	void mobHit(int handle, double damage, const DVector3 &from)
	{
		AActor *monster = nullptr;
		for (auto &entry : monstersNow)
			if (entry.first == handle)
				monster = entry.second;
		if (monster == nullptr)
			return;
		// the mob that did it, so the monster turns on it
		AActor *attacker = nullptr;
		double best = 3 * UNITS;
		for (AActor *proxy : proxiesNow)
		{
			const double d = (proxy->Pos() - from).Length();
			if (d < best)
			{
				best = d;
				attacker = proxy;
			}
		}
		const bool alive = monster->health > 0;
		const DVector3 chest = monster->PosPlusZ(monster->Height * 0.6);
		DVector3 dir = monster->Pos() - from;
		dir.Z = 0;
		if (dir.Length() > 0.1)
			dir /= dir.Length();
		P_DamageMobj(monster, attacker, attacker, max(1, static_cast<int>(damage * MC_TO_DOOM)), NAME_Melee);
		// The blow shows on both sides: Doom's blood where it landed and the monster staggering, and Minecraft's own
		// marks of a hit (the dark hearts of damage dealt, a sweep), and a puff of smoke if that was the end of it.
		P_SpawnBlood(chest, dir.Angle() + DAngle::fromDeg(180.), static_cast<int>(damage * MC_TO_DOOM), monster);
		flinch(monster, dir);
		FString c;
		c.Format("particle minecraft:damage_indicator %.2f %.2f %.2f 0.25 0.3 0.25 0.2 %d force", mcX(chest.X), mcY(chest.Z), mcZ(chest.Y),
			clamp(static_cast<int>(damage * 2), 3, 14));
		sendCommand(c.GetChars());
		c.Format("particle minecraft:sweep_attack %.2f %.2f %.2f 0 0 0 0 1 force", mcX(chest.X - dir.X * 12), mcY(chest.Z), mcZ(chest.Y - dir.Y * 12));
		sendCommand(c.GetChars());
		if (alive && monster->health <= 0)
		{
			c.Format("particle minecraft:poof %.2f %.2f %.2f 0.3 0.5 0.3 0.05 20 force", mcX(chest.X), mcY(chest.Z), mcZ(chest.Y));
			sendCommand(c.GetChars());
		}
	}

	void tickFighters(FLevelLocals *Level)
	{
		// what Doom did to the mobs' proxies goes to the mobs; a proxy itself never dies
		for (size_t i = 0; i < proxiesNow.size();)
		{
			AActor *proxy = proxiesNow[i];
			const int id = proxy->special1;
			auto seen = proxySeen.find(id);
			if (seen == proxySeen.end() || tic - seen->second > 35)
			{
				// the mob is dead or gone
				proxySeen.erase(id);
				proxy->Destroy();
				proxiesNow.erase(proxiesNow.begin() + i);
				continue;
			}
			if (proxy->health < PROXY_HEALTH)
			{
				FString m;
				m.Format("{\"t\":\"mobdmg\",\"id\":%d,\"d\":%.2f}", id, (PROXY_HEALTH - proxy->health) * DOOM_TO_MC);
				send(m);
				// (a monster has hurt the mob: Minecraft's marks of it on the mob, sparks and the dark hearts)
				const DVector3 at = proxy->PosPlusZ(proxy->Height * 0.6);
				const double hurt = (PROXY_HEALTH - proxy->health) * DOOM_TO_MC;
				m.Format("particle minecraft:damage_indicator %.2f %.2f %.2f 0.25 0.3 0.25 0.2 %d force", mcX(at.X), mcY(at.Z), mcZ(at.Y),
					clamp(static_cast<int>(hurt), 2, 12));
				sendCommand(m.GetChars());
				m.Format("particle minecraft:crit %.2f %.2f %.2f 0.3 0.4 0.3 0.4 %d force", mcX(at.X), mcY(at.Z), mcZ(at.Y), clamp(static_cast<int>(hurt * 2), 4, 16));
				sendCommand(m.GetChars());
				proxy->health = PROXY_HEALTH;
			}
			i++;
		}

		// Doom's monsters to Minecraft
		if ((tic & 3) == 0 && (!monstersNow.empty() || sentPeds))
		{
			FString m = "{\"t\":\"peds\",\"p\":[";
			int count = 0;
			for (auto &entry : monstersNow)
			{
				const DVector3 pos = entry.second->Pos();
				m.AppendFormat("%s[%d,%.3f,%.3f,%.3f,%.2f]", count++ == 0 ? "" : ",", entry.first, mcX(pos.X), mcY(pos.Z), mcZ(pos.Y),
					entry.second->radius / UNITS);
				if (count >= 64)
					break;
			}
			m += "]}";
			send(m);
			sentPeds = count > 0;
		}

		// a monster with nothing better to do picks a fight with a mob it can see
		if ((tic % 18) == 0 && !proxiesNow.empty())
		{
			for (auto &entry : monstersNow)
			{
				AActor *monster = entry.second;
				AActor *target = monster->target;
				if (target != nullptr && target->player == nullptr && target->health > 0)
					continue;	// already fighting a mob or another monster
				AActor *nearest = nullptr;
				double best = MOB_SIGHT;
				if (target != nullptr)
					best = min(best, monster->Distance2D(target));
				for (AActor *proxy : proxiesNow)
				{
					const double d = monster->Distance2D(proxy);
					if (d < best && P_CheckSight(monster, proxy))
					{
						best = d;
						nearest = proxy;
					}
				}
				if (nearest != nullptr)
				{
					const bool idle = target == nullptr;
					monster->target = nearest;
					if (idle && monster->SeeState != nullptr)
						monster->SetState(monster->SeeState);
				}
			}
		}
	}

	void keepCompanion(FLevelLocals *Level, AActor *player)
	{
		if ((tic % 35) != 0 || player->health <= 0)
			return;
		PClassActor *type = PClass::FindActor("MCPurin");
		if (type == nullptr)
			return;
		AActor *companion = nullptr;
		auto it = Level->GetThinkerIterator<AActor>();
		while (AActor *actor = it.Next())
		{
			if (actor->IsKindOf(type) && actor->health > 0)
			{
				companion = actor;
				break;
			}
		}
		if (!mc_companion)
		{
			// sent away (mc_companion 0, or the Minecraft page of the options menu): he goes at once
			if (companion != nullptr)
				companion->Destroy();
			return;
		}
		// behind the player, at the player's feet
		const DAngle back = player->Angles.Yaw + DAngle::fromDeg(180.);
		const DVector3 spot = player->Pos() + DVector3(back.Cos() * 40, back.Sin() * 40, 0);
		if (companion == nullptr)
		{
			companion = Spawn(Level, type, player->Pos(), NO_REPLACE);
			if (companion != nullptr && !P_TeleportMove(companion, spot, false))
				companion->SetOrigin(player->Pos(), false);
		}
		else if (companion->Distance2D(player) > 1400 || fabs(companion->Z() - player->Z()) > 400)
		{
			// left behind (a lift, a teleporter, a run across the map): it catches up
			if (!P_TeleportMove(companion, spot, false))
				P_TeleportMove(companion, player->Pos(), false);
			companion->target = nullptr;
		}
	}

	//==========================================================================
	//
	// Minecraft's movement
	//
	// Doom's player slides: its thrust and friction give a long run-up and a long stop. Steve doesn't: on the ground
	// he is at full speed in a few ticks and stops as fast, in the air he keeps what he had and steers very little,
	// and a jump while sprinting throws him forward. So Doom's own thrust is switched off (its move factors are 0)
	// and the player's velocity is set here every tic by Minecraft's rules, converted from its 20 ticks a second and
	// blocks to Doom's 35 tics and map units (32 to a block). Doom still does the moving and the colliding.
	//
	// Minecraft, per tick: v = v * friction + acceleration * input, with friction 0.546 on the ground (0.6 block
	// slipperiness * 0.91) and 0.91 in the air; walking tops out at 4.317 blocks a second and sprinting at 5.612.
	// Per Doom tic that is friction 0.546^(20/35) = 0.708 and 0.91^(20/35) = 0.948, and top speeds of 3.95 and 5.13
	// units a tic; the acceleration is whatever makes the top speed come out (top * (1 - friction)). In the air
	// Minecraft's acceleration is a fifth of the ground's: top 4.06 (5.28 sprinting) at friction 0.948.
	// A jump is 1.25 blocks high and lasts 12 ticks: 40 units in 21 tics, which is 7.62 units a tic up against a
	// gravity of 0.726. A sprint-jump adds 0.2 blocks a tick forward: 3.66 units a tic.
	//
	//==========================================================================

	const double STEVE_GROUND_FRICTION = 0.708, STEVE_AIR_FRICTION = 0.948;
	const double STEVE_WALK = 3.947, STEVE_SPRINT = 5.131;
	const double STEVE_AIR_WALK = 4.063, STEVE_AIR_SPRINT = 5.282;
	const double STEVE_JUMP = 7.62, STEVE_GRAVITY = 0.726, STEVE_JUMP_BOOST = 3.657;

	bool steveOn;
	bool steveHasLast;
	DVector3 steveLast;
	bool steveWasOnGround;

	void steveOff(AActor *mo)
	{
		if (!steveOn)
			return;
		steveOn = false;
		steveHasLast = false;
		mo->FloatVar(NAME_ForwardMove1) = mo->FloatVar(NAME_ForwardMove2) = 1.0;
		mo->FloatVar(NAME_SideMove1) = mo->FloatVar(NAME_SideMove2) = 1.0;
		mo->FloatVar(NAME_JumpZ) = 8.0;
		mo->Gravity = 1.0;
	}

	// Once a tic, after Doom has moved the player (and after the tunnel has).
	void steveMove(player_t *player)
	{
		AActor *mo = player->mo;
		if (!mc_steve_move || player->health <= 0 || (player->cheats & CF_NOCLIP) != 0 && !tunnelling)
		{
			steveOff(mo);
			return;
		}

		steveOn = true;
		// no thrust from Doom: the velocity below is all there is
		mo->FloatVar(NAME_ForwardMove1) = mo->FloatVar(NAME_ForwardMove2) = 0.0;
		mo->FloatVar(NAME_SideMove1) = mo->FloatVar(NAME_SideMove2) = 0.0;
		mo->FloatVar(NAME_JumpZ) = STEVE_JUMP;
		mo->Gravity = STEVE_GRAVITY;

		// what the player actually did this tic: walls, knockback and steps are all in it
		const DVector3 pos = mo->Pos();
		DVector2 v(0, 0);
		if (steveHasLast)
		{
			v = DVector2(pos.X - steveLast.X, pos.Y - steveLast.Y);
			if (v.LengthSquared() > 48.0 * 48.0)
				v = DVector2(0, 0);		// a teleporter, not a step
		}
		steveLast = pos;
		steveHasLast = true;

		const bool onGround = tunnelling ? tunnelGround : (pos.Z <= mo->floorz + 0.01 || (mo->flags2 & MF2_ONMOBJ) != 0);

		// the keys, as a direction: forward and right, not faster on the diagonal
		const double forward = player->cmd.ucmd.forwardmove, side = player->cmd.ucmd.sidemove;
		DVector2 wish(0, 0);
		const double held = sqrt(forward * forward + side * side);
		// sprinting is forward only (Doom's run key makes the forward move at least 0x3200)
		const bool sprint = forward >= 0x3000;
		const DAngle yaw = mo->Angles.Yaw;
		const DVector2 ahead(yaw.Cos(), yaw.Sin()), right(yaw.Sin(), -yaw.Cos());
		if (held > 0)
			wish = (ahead * forward + right * side) / held;

		if (onGround)
			v = v * STEVE_GROUND_FRICTION + wish * ((sprint ? STEVE_SPRINT : STEVE_WALK) * (1.0 - STEVE_GROUND_FRICTION));
		else
			v = v * STEVE_AIR_FRICTION + wish * ((sprint ? STEVE_AIR_SPRINT : STEVE_AIR_WALK) * (1.0 - STEVE_AIR_FRICTION));

		// leaving the ground by jumping while sprinting: the leap forward
		const bool jumped = steveWasOnGround && !onGround && (player->cmd.ucmd.buttons & BT_JUMP) != 0
			&& (tunnelling ? tunnelVelZ > 0 : mo->Vel.Z > 0);
		if (jumped && sprint)
			v += ahead * STEVE_JUMP_BOOST;
		steveWasOnGround = onGround;

		if (v.LengthSquared() < 0.01 && held == 0)
			v = DVector2(0, 0);
		mo->Vel.X = v.X;
		mo->Vel.Y = v.Y;
		player->Vel = v;	// the view bob goes by this
	}

	// Minecraft's movement keys, as Doom's own controls ask for them this tic.
	void sendMovement(bool active, int forward, int side, int buttons)
	{
		static const char *const names[7] = {"forward", "back", "left", "right", "jump", "sneak", "sprint"};
		const bool want[7] = {
			active && forward > 0, active && forward < 0, active && side < 0, active && side > 0,
			active && (buttons & BT_JUMP) != 0, active && (buttons & BT_CROUCH) != 0,
			active && (abs(forward) > 9000 || abs(side) > 9000),	// Doom's "run" doubles the push
		};
		for (int i = 0; i < 7; i++)
		{
			if (want[i] != moveKeys[i])
			{
				moveKeys[i] = want[i];
				sendKey(names[i], want[i]);
			}
		}
	}

	void stopWalking(player_t *player)
	{
		walking = false;
		if (walkFloats && player->mo != nullptr)
			player->mo->flags &= ~MF_NOGRAVITY;
		walkFloats = false;
	}

	// Once a tic: Doom's player goes where Minecraft's stands. The move is made as one of Doom's own (so that lines
	// crossed do what they do and things touched are picked up); where Doom won't have it (a line it doesn't let
	// through, a monster in the way) the player stays, and if that lasts Minecraft comes back to him. In and beside
	// mined tunnels Doom's map has no say: there he is simply put.
	void walkTick(FLevelLocals *Level, player_t *player)
	{
		AActor *mo = player->mo;
		const bool want = mc_walk && mo != nullptr && player->health > 0 && (player->cheats & CF_NOCLIP) == 0 && tic >= walkHold;
		if (want && !walking)
		{
			walking = true;
			walkSince = tic;
			walkBlocked = 0;
		}
		else if (!want && walking)
		{
			stopWalking(player);
		}
		if (!walking || tic - walkSince < 3 || mcPosTic <= walkSince || tic - mcPosTic > 12)
			return;

		// (carried along its velocity to this tic's place on an even clock: the messages come when they come)
		static double evenClock;
		const double wall = McShm_Nanos() / 1.0e9;
		evenClock += 1.0 / TICRATE;
		if (fabs(evenClock - wall) > 0.1)
			evenClock = wall;
		const double age = clamp(mcPosNanos != 0 ? evenClock - mcPosNanos / 1.0e9 : 0.0, -0.1, 0.15);
		const DVector3 target = toDoom(mcPos[0] + mcVel[0] * age, mcPos[1] + mcVel[1] * age, mcPos[2] + mcVel[2] * age);
		const DVector3 at = mo->Pos();
		if ((target - at).Length() > 8.0 * UNITS)
		{
			// the two have come apart (a teleporter, a death): Minecraft goes back to following Doom's player
			walkHold = tic + 12;
			stopWalking(player);
			return;
		}

		if (minedNear(target, 1.6))
		{
			mo->SetOrigin(target, true);
			mo->floorz = target.Z;
		}
		else
		{
			mo->SetZ(target.Z);
			if (target.X != at.X || target.Y != at.Y)
			{
				if (P_TryMove(mo, target.XY(), true))
				{
					walkBlocked = 0;
				}
				else if (walkBlocker.Format("%s%s, target %.1f %.1f %.1f from %.1f %.1f %.1f, floor %.1f",
					mo->BlockingMobj != nullptr ? mo->BlockingMobj->GetClass()->TypeName.GetChars() : "no thing",
					mo->BlockingLine != nullptr ? " and a line" : "", target.X, target.Y, target.Z, at.X, at.Y, at.Z, mo->floorz),
					++walkBlocked > 14)
				{
					walkHold = tic + 10;
					stopWalking(player);
					return;
				}
			}
			if (mo->Z() < mo->floorz)
				mo->SetZ(mo->floorz);
		}
		mo->Vel.Zero();
		player->Vel.Zero();
		mo->flags |= MF_NOGRAVITY;
		walkFloats = true;
		player->onground = mcGround;
	}

	void reset(FLevelLocals *Level)
	{
		walkHold = tic + 35;	// Minecraft's ground is gone until the columns are sent again
		stopWalking(&players[consoleplayer]);
		typing = false;
		sentColumns.clear();
		columnLight.clear();
		mined.clear();
		gridDirty = true;
		leaveTunnel(&players[consoleplayer]);
		findBand(Level);
		projectiles.clear();
		releaseKeys();
		screenOpen = false;
		sentHealth = sentArmor = -1;
		viewSent = false;
		send("{\"t\":\"reset\"}");
		// (the pointer over Minecraft's screens is drawn here, where the mouse is now: in Minecraft's picture, which
		// is shown a frame or two late, it trailed behind the hand)
		send("{\"t\":\"caps\",\"cursor\":true}");
		currentLevel = Level;
		currentMap = Level->MapName;
		currentMapTime = Level->maptime;
	}
}

//==========================================================================
//
// Engine hooks
//
//==========================================================================

void MCBridge_Startup()
{
	if (!mc_passthrough || started)
		return;
	started = true;
	sysCallbacks.WantEscape = MCBridge_WantEscape;
	McShm_InstallCrashLog();
	link.start("127.0.0.1", mc_port);
	Printf("Minecraft passthrough: waiting for Minecraft on 127.0.0.1:%d\n", *mc_port);
}

void MCBridge_Shutdown()
{
	if (started)
	{
		releaseKeys();
		link.stop();
		started = false;
	}
}

// For testing without sitting at it: console commands written to a file "mc-test.txt" in the working folder are
// carried out and the file removed (GZDoom's own commands, "wait <tics>" among them, and the mc_ ones below:
// "+forward; wait 70; -forward; mc_state; screenshot a.png"). mc_state writes where things stand to mc-test.log.
static void testCommands()
{
	if ((tic % 4) != 0)
		return;
	FILE *file = fopen("mc-test.txt", "r");
	if (file == nullptr)
		return;
	FString all;
	char line[512];
	while (fgets(line, sizeof(line), file) != nullptr)
	{
		FString one = line;
		one.StripLeftRight();
		if (one.IsNotEmpty())
		{
			if (all.IsNotEmpty()) all += "; ";
			all += one;
		}
	}
	fclose(file);
	remove("mc-test.txt");
	if (all.IsNotEmpty())
		AddCommandString(all.GetChars());
}

void MCBridge_Ticker()
{
	if (!started)
		return;
	tic++;
	testCommands();
	FLevelLocals *Level = primaryLevel;
	std::string text;
	if (!inGame())
	{
		while (link.poll(text)) {}
		return;
	}

	AActor *player = players[consoleplayer].mo;
	// Minecraft's cubes are cubes: no 1.2 tall pixels while it is drawn into the view
	Level->pixelstretch = 1.f;
	if (Level->info != nullptr)
		Level->info->pixelstretch = 1.f;

	keepCompanion(Level, player);

	if (!link.connected())
	{
		while (link.poll(text)) {}
		return;
	}

	if (generation != link.generation() || Level != currentLevel || currentMap.Compare(Level->MapName) != 0 || Level->maptime < currentMapTime
		|| pendingLoad.IsNotEmpty() || pendingNew)
	{
		// a new connection or a new map: Minecraft starts from nothing
		removeBlockActors(Level);
		generation = link.generation();
		reset(Level);
		if (pendingNew)
		{
			pendingNew = false;
			send("{\"t\":\"newgame\"}");
		}
		if (pendingLoad.IsNotEmpty())
		{
			// (after the "reset": the map is empty in Minecraft, and what went with this game is put back into it)
			FString m;
			m.Format("{\"t\":\"load\",\"id\":\"%s\"}", pendingLoad.GetChars());
			send(m);
			pendingLoad = "";
		}
	}
	currentMapTime = Level->maptime;

	chooseHud();
	if (player->FloatVar(NAME_ViewHeight) != mc_viewheight && mc_viewheight >= 20.f && mc_viewheight <= 54.f)
		player->FloatVar(NAME_ViewHeight) = mc_viewheight;

	// the hearts and the armour bar are Doom's health and armour
	{
		AActor *armor = player->FindInventory(NAME_BasicArmor);
		const int health = players[consoleplayer].health;
		const int points = armor != nullptr ? armor->IntVar(NAME_Amount) : 0;
		if (health != sentHealth || points != sentArmor || (tic % 35) == 0)	// (the repeat also carries mc_spawns)
		{
			sentHealth = health;
			sentArmor = points;
			FString m;
			m.Format("{\"t\":\"hp\",\"h\":%d,\"a\":%d,\"sp\":%d}", health, points, *mc_spawns ? 1 : 0);
			send(m);
		}
	}

	collectFighters(Level, player);
	while (link.poll(text))
		handle(Level, player, text);
	tickFighters(Level);

	sendColumns(Level, player);
	walkTick(Level, &players[consoleplayer]);
	if (walking)
	{
		leaveTunnel(&players[consoleplayer]);
		steveOff(player);
	}
	else
	{
		moveInTunnel(Level, &players[consoleplayer]);
		steveMove(&players[consoleplayer]);
	}
	if (typing && (typingOpened ? !screenOpen : tic - typingSince > 50))
		typing = false;	// sent, cancelled, or it never opened
	typingOpened = typing && (typingOpened || screenOpen);
	buildGrid(players[consoleplayer].mo);

	if ((tic & 63) == 0)
	{
		for (auto it = projectiles.begin(); it != projectiles.end();)
			it = tic - it->second.seen > 70 ? projectiles.erase(it) : std::next(it);
	}
}

void MCBridge_Frame(const FRenderViewpoint &vp, float proj0, float proj5, int width, int height)
{
	now.valid = false;
	if (!started || !inGame() || !link.connected() || proj0 == 0.f || proj5 == 0.f)
		return;

	// Doom stands still (paused, its menu or its console open): Minecraft's world stands still too. Its mobs went
	// on walking and fighting, and its day went on, behind Doom's menu. ("tick freeze" stops everything there but
	// the player; this is looked at here and not in the ticker, which does not run while Doom is stopped.)
	{
		static int said = -1, saidFrame;
		const bool stopped = !netgame && (paused || menuactive == MENU_On || ConsoleState == c_down || ConsoleState == c_falling);
		if ((stopped ? 1 : 0) != said || ++saidFrame > 5000)	// (said again now and then: Minecraft may have come later)
		{
			said = stopped ? 1 : 0;
			saidFrame = 0;
			sendCommand(stopped ? "tick freeze" : "tick unfreeze");
		}
	}

	double yaw = -vp.Angles.Yaw.Degrees() - 90.0;
	yaw -= 360.0 * floor((yaw + 180.0) / 360.0);
	now.pos[0] = mcX(vp.Pos.X);
	now.pos[1] = mcY(vp.Pos.Z);
	now.pos[2] = mcZ(vp.Pos.Y);
	now.yaw = static_cast<float>(yaw);
	now.pitch = vp.HWAngles.Pitch.Degrees();
	now.tanX = 1.f / proj0;
	now.tanY = 1.f / proj5;
	now.light = vp.sector != nullptr ? clamp(vp.sector->lightlevel / 255.f, 0.f, 1.f) : 1.f;
	now.valid = true;

	if (!viewSent || width != viewWidth || height != viewHeight)
	{
		viewSent = true;
		viewWidth = width;
		viewHeight = height;
		FString view;
		view.Format("{\"t\":\"view\",\"w\":%d,\"h\":%d}", width, height);
		send(view);
	}

	if (screenOpen && cursorMoved)
	{
		cursorMoved = false;
		FString cursor;
		cursor.Format("{\"t\":\"mouse\",\"x\":%.1f,\"y\":%.1f}", cursorX, cursorY);
		send(cursor);
	}

	AActor *camera = vp.camera;
	const DVector3 feet = camera != nullptr ? camera->InterpolatedPosition(vp.TicFrac) : vp.Pos;
	double bodyYaw = camera != nullptr ? -camera->Angles.Yaw.Degrees() - 90.0 : yaw;
	bodyYaw -= 360.0 * floor((bodyYaw + 180.0) / 360.0);
	// While Minecraft moves him: it walks where the view looks, on the map's columns as blocks (they have the
	// ceilings, the doors and the mined tunnels), and steps up what Doom's player steps up (24 units).
	FString walk;
	if (walking)
		walk.Format(",\"walk\":true,\"look\":[%.3f,%.3f],\"blocks\":true,\"step\":0.76", now.yaw, now.pitch);
	// Minecraft's picture of this camera is shown a few hundredths of a second from now, by which time a turning
	// view has turned further. It is asked for where the view will be looking then (the way it is turning, carried
	// on), so that what it draws is in the middle of what is needed and not trailing behind it: turning fast next to
	// blocks, the side being turned towards had nothing drawn in it yet, and the blocks there were gone for a moment.
	// (The picture is still put on screen by the camera it was really drawn with, so a wrong guess costs nothing.)
	static double lastYaw, lastPitch, yawRate, pitchRate;
	static uint64_t lastNanos;
	const uint64_t nanos = McShm_Nanos();
	const double elapsed = (nanos - lastNanos) / 1.0e9;
	double turned = now.yaw - lastYaw;
	turned -= 360.0 * floor((turned + 180.0) / 360.0);
	if (lastNanos != 0 && elapsed > 0.0005 && elapsed < 0.1 && fabs(turned) < 40.0)
	{
		// (smoothed a little: one frame's mouse movement is uneven)
		yawRate += (turned / elapsed - yawRate) * 0.6;
		pitchRate += ((now.pitch - lastPitch) / elapsed - pitchRate) * 0.6;
	}
	else
	{
		yawRate = pitchRate = 0.0;	// a cut (a teleporter, a new map): no turning to carry on
	}
	lastYaw = now.yaw;
	lastPitch = now.pitch;
	lastNanos = nanos;
	const double ahead = clamp<double>(mc_predict, 0.0, 0.15);
	double askYaw = now.yaw + clamp(yawRate * ahead, -25.0, 25.0);
	askYaw -= 360.0 * floor((askYaw + 180.0) / 360.0);
	const double askPitch = clamp(now.pitch + clamp(pitchRate * ahead, -15.0, 15.0), -89.9, 89.9);
	FString m;
	m.Format("{\"t\":\"cam\",\"f\":%lld,\"p\":[%.4f,%.4f,%.4f],\"r\":[%.3f,%.3f,0],\"fov\":%.4f,\"fp\":%s,\"pl\":[%.4f,%.4f,%.4f],\"h\":%.2f%s}",
		++frameCounter, now.pos[0], now.pos[1], now.pos[2], askYaw, askPitch,
		2.0 * atan(now.tanY * clamp<double>(mc_overscan, 1.0, 2.0)) * 180.0 / PI, vp.showviewer ? "false" : "true",
		mcX(feet.X), mcY(feet.Z), mcZ(feet.Y), bodyYaw, walk.GetChars());
	send(m);
}

static bool bridgeHasInput()
{
	return started && mc_hands && inGame() && link.connected() && menuactive == MENU_Off && ConsoleState == c_up && !paused;
}

bool MCBridge_WantEscape()
{
	return screenOpen && bridgeHasInput();
}

bool MCBridge_EarlyEvent(event_t *ev)
{
	if (ev->type != EV_Mouse || !screenOpen || !bridgeHasInput())
		return false;
	// the mouse moves Minecraft's cursor instead of the view
	cursorX = clamp(cursorX + ev->x * mc_cursor_speed, 0.0, viewWidth - 1.0);
	cursorY = clamp(cursorY - ev->y * mc_cursor_speed, 0.0, viewHeight - 1.0);
	cursorMoved = true;
	return true;
}

bool MCBridge_Responder(event_t *ev)
{
	if (!bridgeHasInput())
		return false;
	if (ev->type != EV_KeyDown && ev->type != EV_KeyUp)
		return false;

	const bool down = ev->type == EV_KeyDown;
	if (ev->data1 == KEY_LSHIFT)
		shiftDown = down;	// not consumed: it is also Doom's run key

	if (typing)
	{
		// chat: the keyboard types into Minecraft, and nothing is a Doom key
		if (down)
		{
			const char *special = nullptr;
			switch (ev->data1)
			{
			case KEY_ENTER: special = "enter"; break;
			case KEY_BACKSPACE: special = "backspace"; break;
			case KEY_ESCAPE: special = "escape"; break;
			case KEY_DEL: special = "delete"; break;
			case KEY_LEFTARROW: special = "left"; break;
			case KEY_RIGHTARROW: special = "right"; break;
			case KEY_UPARROW: special = "up"; break;
			case KEY_DOWNARROW: special = "down"; break;
			case KEY_HOME: special = "home"; break;
			case KEY_END: special = "end"; break;
			case KEY_TAB: special = "tab"; break;
			default: break;
			}
			FString m;
			if (special != nullptr)
			{
				m.Format("{\"t\":\"keypress\",\"k\":\"%s\"}", special);
				send(m);
			}
			else if (ev->data1 < 0x80)
			{
				const int c = McShm_KeyChar(ev->data1);
				if (c != 0)
				{
					m.Format("{\"t\":\"char\",\"c\":%d}", c);
					send(m);
				}
			}
		}
		return ev->data1 != KEY_LSHIFT && ev->data1 != KEY_RSHIFT;
	}

	if (screenOpen)
	{
		// everything but walking belongs to the screen
		if (ev->data1 >= KEY_MOUSE1 && ev->data1 <= KEY_MOUSE3)
		{
			const int b = ev->data1 - KEY_MOUSE1;
			heldButton[b] = down;
			FString m;
			m.Format("{\"t\":\"click\",\"b\":%d,\"down\":%s,\"mods\":%d}", b, down ? "true" : "false", shiftDown ? 1 : 0);
			send(m);
			return true;
		}
		if (ev->data1 == KEY_ESCAPE)
		{
			if (down)
				sendKey("escape", true);
			return true;
		}
		if (ev->data1 == KEY_MWHEELUP || ev->data1 == KEY_MWHEELDOWN)
		{
			if (down)
				send(ev->data1 == KEY_MWHEELUP ? "{\"t\":\"scroll\",\"d\":1}" : "{\"t\":\"scroll\",\"d\":-1}");
			return true;
		}
		// The keyboard is the screen's too, as in Minecraft: E shuts an inventory, the numbers swap with the hotbar,
		// and a text box (the creative search) is typed into. Minecraft decides which; Doom's player stands still.
		if (down)
		{
			const char *special = nullptr;
			switch (ev->data1)
			{
			case KEY_ENTER: special = "enter"; break;
			case KEY_BACKSPACE: special = "backspace"; break;
			case KEY_DEL: special = "delete"; break;
			case KEY_LEFTARROW: special = "left"; break;
			case KEY_RIGHTARROW: special = "right"; break;
			case KEY_UPARROW: special = "up"; break;
			case KEY_DOWNARROW: special = "down"; break;
			case KEY_HOME: special = "home"; break;
			case KEY_END: special = "end"; break;
			case KEY_TAB: special = "tab"; break;
			default: break;
			}
			FString m;
			if (special != nullptr)
			{
				m.Format("{\"t\":\"keypress\",\"k\":\"%s\"}", special);
				send(m);
			}
			else if (ev->data1 < 0x80)
			{
				const int c = McShm_KeyChar(ev->data1);
				if (c != 0)
				{
					m.Format("{\"t\":\"char\",\"c\":%d}", c);
					send(m);
				}
			}
		}
		return ev->data1 != KEY_LSHIFT && ev->data1 != KEY_RSHIFT;
	}

	if (ev->data1 == 0x14)	// T: Minecraft's chat
	{
		if (down)
		{
			releaseKeys();
			typing = true;
			typingOpened = false;
			typingSince = tic;
			sendKey("chat", true);
			sendKey("chat", false);
		}
		return true;
	}
	if (ev->data1 == KEY_F1)	// Minecraft's: hide the HUD
	{
		if (down)
		{
			hudHidden = !hudHidden;
			chooseHud();
		}
		return true;
	}
	if (ev->data1 == KEY_F3)	// Minecraft's: the debug screen
	{
		if (down)
			sendKey("debug", true);
		return true;
	}
	if (ev->data1 == KEY_F5)	// Minecraft's: first / third person (Doom's chase camera)
	{
		if (down)
			C_DoCommand("chase");
		return true;
	}
	if (ev->data1 == 0x12)	// E: the inventory
	{
		sendKey("inventory", down);
		return true;
	}
	if (ev->data1 == 0x10)	// Q: drop the held item
	{
		sendKey("drop", down);
		return true;
	}

	switch (ev->data1)
	{
	case KEY_MOUSE1:
		heldAttack = down;
		sendKey("attack", down);
		return true;
	case KEY_MOUSE2:
		heldUse = down;
		sendKey("use", down);
		return true;
	case KEY_MOUSE3:
		heldPick = down;
		sendKey("pick", down);
		return true;
	case KEY_MWHEELUP:
	case KEY_MWHEELDOWN:
		if (down)
			send(ev->data1 == KEY_MWHEELUP ? "{\"t\":\"scroll\",\"d\":1}" : "{\"t\":\"scroll\",\"d\":-1}");
		return true;
	default:
		break;
	}

	// the number row (DIK_1 .. DIK_9) picks a hotbar slot
	if (ev->data1 >= 0x02 && ev->data1 <= 0x0A)
	{
		if (down)
		{
			FString m;
			m.Format("{\"t\":\"slot\",\"n\":%d}", ev->data1 - 0x02);
			send(m);
		}
		return true;
	}
	return false;
}

void MCBridge_FilterTiccmd(ticcmd_t *cmd)
{
	// no invisible gun going off while the hands are Minecraft's
	if (MCBridge_HideWeapon())
		cmd->ucmd.buttons &= ~(BT_ATTACK | BT_ALTATTACK);
}

// Doom's saved games. Minecraft keeps, beside each (by the file's name), what goes with it: what the player
// carries, what he has built and mined in the map. It is told when a game is saved, loaded and deleted.
static FString saveId(const char *filename)
{
	FString name = filename != nullptr ? filename : "";
	ptrdiff_t slash = max(name.LastIndexOf("/"), name.LastIndexOf("\\"));
	if (slash >= 0)
		name = name.Mid(slash + 1);
	ptrdiff_t dot = name.LastIndexOf(".");
	if (dot > 0)
		name = name.Left(dot);
	FString id;
	for (size_t i = 0; i < name.Len(); i++)
	{
		const char c = name[i];
		id += ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') ? c : '_';
	}
	return id;
}

void MCBridge_GameSaved(const char *filename)
{
	if (!started || !link.connected())
		return;
	// (Doom saves by itself on coming into a map, before this bridge has emptied Minecraft's map of the last one's
	// things: then only what the player carries goes with the save, not blocks that belong to another map)
	FLevelLocals *Level = primaryLevel;
	const bool stale = Level == nullptr || Level != currentLevel || currentMap.Compare(Level->MapName) != 0;
	FString m;
	m.Format("{\"t\":\"save\",\"id\":\"%s\",\"carried\":%s}", saveId(filename).GetChars(), stale ? "true" : "false");
	send(m);
}

// A new game (from Doom's menu, or the "map" command): Minecraft's side starts afresh too. The map is emptied as
// for any new map; and the player, who otherwise keeps what he carries from map to map, starts again with what a new
// player starts with.
void MCBridge_NewGame()
{
	pendingNew = true;
	pendingLoad = "";
}

void MCBridge_GameLoaded(const char *filename)
{
	// (acted on in the ticker, once the loaded map is the one being played: Minecraft's map is emptied first)
	pendingLoad = saveId(filename);
}

void MCBridge_SaveDeleted(const char *filename)
{
	if (!started || !link.connected())
		return;
	FString m;
	m.Format("{\"t\":\"savedel\",\"id\":\"%s\"}", saveId(filename).GetChars());
	send(m);
}

void MCBridge_FilterMovement(ticcmd_t *cmd)
{
	// Minecraft moves the player: Doom's movement controls are passed on to it, and Doom itself gets none of them.
	// (Here and not in MCBridge_FilterTiccmd: that runs before the movement and the jump are in the command.)
	const bool pass = walking && started && inGame() && link.connected() && !screenOpen && !typing;
	sendMovement(pass, cmd->ucmd.forwardmove, cmd->ucmd.sidemove, cmd->ucmd.buttons);
	if (walking)
	{
		cmd->ucmd.forwardmove = cmd->ucmd.sidemove = cmd->ucmd.upmove = 0;
		cmd->ucmd.buttons &= ~(BT_JUMP | BT_CROUCH);
	}
}

bool MCBridge_Active()
{
	return started && inGame() && link.connected();
}

bool MCBridge_HideWeapon()
{
	return started && mc_hands && inGame() && link.connected();
}

bool MCBridge_GetLayers(McLayers &layers)
{
	if (!started || !now.valid)
		return false;
	McFrame frame;
	if (!McShm_Latest(frame))
		return false;

	// rows of the world -> view rotation for a Minecraft camera (x right, y up, z back)
	auto basis = [](float yawDegrees, float pitchDegrees, double m[3][3])
	{
		const double yaw = yawDegrees * PI / 180.0, pitch = pitchDegrees * PI / 180.0;
		const double f[3] = {-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)};
		const double r[3] = {-cos(yaw), 0.0, -sin(yaw)};
		const double u[3] = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
		for (int i = 0; i < 3; i++)
		{
			m[0][i] = r[i];
			m[1][i] = u[i];
			m[2][i] = -f[i];
		}
	};
	double mNow[3][3], mOld[3][3];
	basis(now.yaw, now.pitch, mNow);
	basis(frame.yaw, frame.pitch, mOld);
	const double moved[3] = {now.pos[0] - frame.cam[0], now.pos[1] - frame.cam[1], now.pos[2] - frame.cam[2]};
	for (int i = 0; i < 3; i++)
	{
		for (int j = 0; j < 3; j++)
			layers.rot[i][j] = static_cast<float>(mOld[i][0] * mNow[j][0] + mOld[i][1] * mNow[j][1] + mOld[i][2] * mNow[j][2]);
		layers.rot[i][3] = static_cast<float>(mOld[i][0] * moved[0] + mOld[i][1] * moved[1] + mOld[i][2] * moved[2]);
	}

	layers.serial = frame.serial;
	layers.width = frame.width;
	layers.height = frame.height;
	layers.color = frame.color;
	layers.depth = frame.depth;
	layers.overlay = frame.overlay;
	layers.tanNow[0] = now.tanX;
	layers.tanNow[1] = now.tanY;
	layers.tanMc[1] = static_cast<float>(tan(frame.fov * PI / 360.0));
	layers.tanMc[0] = layers.tanMc[1] * frame.width / frame.height;
	layers.nearPlane = frame.nearPlane;
	layers.farPlane = frame.farPlane;
	layers.zeroToOne = (frame.flags & 1) != 0;
	layers.reversedZ = (frame.flags & 4) != 0;
	// Minecraft's picture is lit by its own daylight: bring it down towards the room's light, never to black
	layers.light = 0.35f + 0.65f * now.light;
	layers.sectorLight = now.light * 255.f;
	layers.gridUsed = gridUsed && !grid.empty();
	layers.anyMined = !mined.empty();
	layers.gridSerial = gridSerial;
	layers.grid = grid.data();
	layers.gridSide = GRID_XZ * 8;
	for (int i = 0; i < 3; i++)
	{
		layers.camInGrid[i] = static_cast<float>(now.pos[i] - gridOrigin[i]);
		for (int j = 0; j < 3; j++)
			layers.viewToWorld[i][j] = static_cast<float>(mNow[j][i]);
	}
	layers.cursorShown = screenOpen && mc_hands && viewWidth > 0 && viewHeight > 0;
	layers.cursor[0] = viewWidth > 0 ? static_cast<float>(cursorX / viewWidth) : 0.5f;
	layers.cursor[1] = viewHeight > 0 ? static_cast<float>(1.0 - cursorY / viewHeight) : 0.5f;
	layers.drawWorld = (mc_layers & 1) != 0;
	layers.drawOverlay = (mc_layers & 2) != 0;
	return true;
}

CCMD(mc_toggle)
{
	releaseKeys();
	if (screenOpen)
		sendKey("escape", true);
	mc_hands = !mc_hands;
	chooseHud();
	Printf("%s\n", mc_hands ? "Minecraft hands" : "Doom weapons");
}

CCMD(mc_key)
{
	// mc_key <attack|use|inventory|drop|escape|...> <1|0>: one of Minecraft's keys, as the mouse or keyboard would send it
	if (argv.argc() >= 3)
		sendKey(argv[1], atoi(argv[2]) != 0);
}

CCMD(mc_turn)
{
	// mc_turn <yaw> <pitch>: the player's view turned by so many degrees (left and up are positive)
	AActor *mo = players[consoleplayer].mo;
	if (argv.argc() >= 3 && mo != nullptr)
	{
		mo->Angles.Yaw += DAngle::fromDeg(atof(argv[1]));
		mo->Angles.Pitch = DAngle::fromDeg(clamp(mo->Angles.Pitch.Degrees() - atof(argv[2]), -89.0, 89.0));
	}
}

CCMD(mc_state)
{
	FILE *file = fopen("mc-test.log", "a");
	if (file == nullptr)
		return;
	AActor *mo = players[consoleplayer].mo;
	if (mo == nullptr || !inGame())
	{
		fprintf(file, "state: not in a level (connected %d)\n", link.connected() ? 1 : 0);
	}
	else
	{
		fprintf(file, "state: tic %d, Doom at %.1f %.1f %.1f (blocks %.2f %.2f %.2f) yaw %.0f pitch %.0f, health %d, %s (blocked %d, hold %d); "
			"Minecraft's player at %.2f %.2f %.2f, said %d tics ago, on ground %d; %d columns sent, %d cells mined, tunnelling %d, hands %d, screen %d\n",
			tic, mo->X(), mo->Y(), mo->Z(), mcX(mo->X()), mcY(mo->Z()), mcZ(mo->Y()), mo->Angles.Yaw.Degrees(), mo->Angles.Pitch.Degrees(),
			players[consoleplayer].health, walking ? "Minecraft walking him" : "Doom moving him", walkBlocked, walkHold - tic,
			mcPos[0], mcPos[1], mcPos[2], tic - mcPosTic, mcGround ? 1 : 0, static_cast<int>(sentColumns.size()), static_cast<int>(mined.size()),
			tunnelling ? 1 : 0, *mc_hands ? 1 : 0, screenOpen ? 1 : 0);
		fprintf(file, "state: last blocked by %s\n", walkBlocker.GetChars());
	}
	fclose(file);
}

CCMD(mc_cmd)
{
	// mc_cmd <Minecraft command...>: e.g. mc_cmd give @a minecraft:tnt 64
	if (argv.argc() < 2)
		return;
	FString command;
	for (int i = 1; i < argv.argc(); i++)
	{
		if (i > 1) command += " ";
		command += argv[i];
	}
	command.Substitute("\\", "\\\\");
	command.Substitute("\"", "\\\"");
	FString m;
	m.Format("{\"t\":\"cmd\",\"c\":\"%s\"}", command.GetChars());
	send(m);
}
