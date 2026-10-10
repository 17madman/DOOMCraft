// Minecraft passthrough: the Doom half of the link to the Minecraft mod (mc/).
//
//   GZDoom                                              Minecraft + Fabric mod
//     mcbridge.cpp  -- WebSocket 127.0.0.1:25599 ---->    camera / map columns / keys / commands
//                   <-------------------------------    explosions, block changes, projectiles, sword swings
//     PPMinecraft (hw_postprocess)  <-- shared memory --  world colour + depth, hand/HUD overlay
//
// Coordinates: 32 map units = 1 block. Doom (x, y, z) -> Minecraft (x / 32, z / 32 + 64, -y / 32);
// Minecraft yaw = -angle - 90, pitch = pitch (positive looks down in both).
#pragma once
#include <cstdint>

struct event_t;
struct FRenderViewpoint;
struct ticcmd_t;

// What the compositing passes need for the frame being drawn.
struct McLayers
{
	uint64_t serial;			// changes when the pixel buffers do
	int width, height;
	const void *color;			// RGBA8, premultiplied, bottom row first
	const void *depth;			// float32
	const void *overlay;		// RGBA8, premultiplied, bottom row first
	float rot[3][4];			// current view space -> the view space the Minecraft frame was rendered in
	float tanNow[2];			// tan(half fov) x, y of Doom's camera
	float tanMc[2];				// the same for Minecraft's frame
	float nearPlane, farPlane;
	bool reversedZ, zeroToOne;
	float light;				// how bright Minecraft's picture is drawn (the light where the player stands)
	float sectorLight;			// the light level where the player stands, 0-255
	bool drawWorld, drawOverlay;
	// mining: the cells around the player (0 open, 1 rock, 2 mined; red channel * 127), 64 x 64 per height in an
	// 8 x 8 atlas of heights
	bool gridUsed;
	bool anyMined;				// false: only the light in the grid matters
	uint64_t gridSerial;
	const void *grid;			// RGBA8, gridSide x gridSide
	int gridSide;
	float camInGrid[3];			// the camera, in blocks from the grid's corner
	float viewToWorld[3][3];	// view space -> Minecraft world axes
};

// Engine hooks
void MCBridge_Startup();
void MCBridge_Shutdown();
void MCBridge_Ticker();													// once per game tic, after the thinkers
void MCBridge_Frame(const FRenderViewpoint &vp, float proj0, float proj5, int width, int height);	// main 3D view, before it is drawn
bool MCBridge_EarlyEvent(event_t *ev);								// before the menu and console: true = consumed
bool MCBridge_WantEscape();												// Escape closes a Minecraft screen, not opens Doom's menu
bool MCBridge_EarlyEvent(event_t *ev);								// before the menu and console: true = consumed
bool MCBridge_WantEscape();												// Escape closes a Minecraft screen, not opens Doom's menu
bool MCBridge_Responder(event_t *ev);									// true: the event went to Minecraft
void MCBridge_FilterTiccmd(ticcmd_t *cmd);
// At the very end of building a tic's command (movement and all buttons are in): while Minecraft moves the
// player, the movement goes to it and Doom gets none.
void MCBridge_FilterMovement(ticcmd_t *cmd);
bool MCBridge_HideWeapon();
bool MCBridge_GetLayers(McLayers &layers);								// false: nothing to composite this frame
