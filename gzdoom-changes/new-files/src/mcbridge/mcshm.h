// Reader for the frames the Minecraft passthrough mod publishes in the named shared memory
// "Local\MCPassthroughFrame" (layout: mc/src/client/java/dev/rehan/passthrough/client/FrameExporter.java).
#pragma once
#include <cstdint>

struct McFrame
{
	uint64_t serial = 0;		// bumped whenever the buffers below hold a new frame
	int width = 0, height = 0;
	float nearPlane = 0.05f, farPlane = 1024.f;
	float fov = 70.f;			// vertical, degrees
	int flags = 0;				// 1 = depth in [0, 1], 2 = rows bottom-up, 4 = reversed Z
	double cam[3] = {};			// the pose the frame was rendered with (Minecraft coordinates)
	float yaw = 0, pitch = 0, roll = 0;
	long long hostFrame = 0;
	const uint8_t *color = nullptr;		// width * height * 4, premultiplied RGBA8
	const float *depth = nullptr;		// width * height
	const uint8_t *overlay = nullptr;	// width * height * 4, premultiplied RGBA8
};

// Copies the newest frame out of the shared memory if there is one. Returns false while Minecraft isn't
// publishing (not running, no host attached, or nothing new for a second).
bool McShm_Latest(McFrame &frame);
// Access violations get a stack trace appended to mc-crash.log in the working directory (GZDoom's own crash
// report only exists if the player saves it from the dialog).
void McShm_InstallCrashLog();
// The character a key types (by its scan code, with Shift and Caps Lock as they are now), or 0.
int McShm_KeyChar(int scanCode);
// A monotonic clock in nanoseconds (QueryPerformanceCounter).
uint64_t McShm_Nanos();
