#include "mcshm.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstring>
#include <vector>

namespace
{
	const wchar_t *const NAME = L"Local\\MCPassthroughFrame";
	const int MAGIC = 0x5450434D;
	const int SLOT_DESC = 256;
	const int SLOT_DESC_BYTES = 128;
	const uint64_t STALE_NANOS = 1000000000ull;
	const uint64_t RETRY_NANOS = 1000000000ull;

	HANDLE mapping;
	const uint8_t *view;
	int headerBytes, slots;
	long long stride;
	uint64_t lastOpenTry;
	long long lastPublished = -1;
	uint64_t lastPublishedAt;
	std::vector<uint8_t> color, overlay;
	std::vector<float> depth;
	McFrame current;

	template<class T> T rd(const uint8_t *p)
	{
		T v;
		memcpy(&v, p, sizeof(T));
		return v;
	}

	bool open()
	{
		if (view != nullptr)
			return true;
		const uint64_t now = McShm_Nanos();
		if (lastOpenTry != 0 && now - lastOpenTry < RETRY_NANOS)
			return false;
		lastOpenTry = now;
		mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, NAME);
		if (mapping == nullptr)
			return false;
		const uint8_t *head = static_cast<const uint8_t *>(MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 4096));
		if (head == nullptr || rd<int>(head) != MAGIC)
		{
			if (head != nullptr)
				UnmapViewOfFile(head);
			CloseHandle(mapping);
			mapping = nullptr;
			return false;
		}
		headerBytes = rd<int>(head + 8);
		slots = rd<int>(head + 12);
		stride = rd<long long>(head + 16);
		UnmapViewOfFile(head);
		view = static_cast<const uint8_t *>(MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, static_cast<SIZE_T>(headerBytes + stride * slots)));
		if (view == nullptr)
		{
			CloseHandle(mapping);
			mapping = nullptr;
			return false;
		}
		return true;
	}
}

int McShm_KeyChar(int scanCode)
{
	const UINT vk = MapVirtualKeyW(static_cast<UINT>(scanCode & 0x7F), MAPVK_VSC_TO_VK);
	if (vk == 0)
		return 0;
	BYTE state[256] = {};
	state[VK_SHIFT] = (GetAsyncKeyState(VK_SHIFT) & 0x8000) ? 0x80 : 0;
	state[VK_CAPITAL] = (GetKeyState(VK_CAPITAL) & 1) ? 1 : 0;
	state[vk] = 0x80;
	WCHAR text[8];
	const int count = ToUnicode(vk, static_cast<UINT>(scanCode & 0x7F), state, text, 8, 0);
	return count > 0 && text[0] >= 32 && text[0] != 127 ? text[0] : 0;
}

uint64_t McShm_Nanos()
{
	static LARGE_INTEGER frequency;
	if (frequency.QuadPart == 0)
		QueryPerformanceFrequency(&frequency);
	LARGE_INTEGER counter;
	QueryPerformanceCounter(&counter);
	return static_cast<uint64_t>(counter.QuadPart / frequency.QuadPart) * 1000000000ull
		+ static_cast<uint64_t>(counter.QuadPart % frequency.QuadPart) * 1000000000ull / frequency.QuadPart;
}

bool McShm_Latest(McFrame &frame)
{
	if (!open())
		return false;

	const uint64_t now = McShm_Nanos();
	const long long published = rd<long long>(view + 32);
	if (published != lastPublished)
	{
		// Minecraft may overwrite the slot while it is copied: its seq is odd during a write and changes after
		for (int attempt = 0; attempt < 4; attempt++)
		{
			const int slot = rd<int>(view + 40);
			if (slot < 0 || slot >= slots)
				break;
			const uint8_t *desc = view + SLOT_DESC + SLOT_DESC_BYTES * slot;
			const long long seq = rd<long long>(desc);
			if (seq & 1)
				continue;
			const int w = rd<int>(desc + 24), h = rd<int>(desc + 28);
			if (w <= 0 || h <= 0 || static_cast<long long>(w) * h * 12 > stride)
				break;
			const size_t n = static_cast<size_t>(w) * h * 4;
			color.resize(n);
			depth.resize(n / 4);
			overlay.resize(n);
			const uint8_t *base = view + headerBytes + stride * slot;
			memcpy(color.data(), base, n);
			memcpy(depth.data(), base + n, n);
			memcpy(overlay.data(), base + 2 * n, n);
			McFrame f;
			f.hostFrame = rd<long long>(desc + 16);
			f.width = w;
			f.height = h;
			f.nearPlane = rd<float>(desc + 32);
			f.farPlane = rd<float>(desc + 36);
			f.fov = rd<float>(desc + 40);
			f.flags = rd<int>(desc + 44);
			f.cam[0] = rd<double>(desc + 48);
			f.cam[1] = rd<double>(desc + 56);
			f.cam[2] = rd<double>(desc + 64);
			f.yaw = rd<float>(desc + 72);
			f.pitch = rd<float>(desc + 76);
			f.roll = rd<float>(desc + 80);
			if (rd<long long>(desc) != seq)
				continue;
			f.serial = current.serial + 1;
			f.color = color.data();
			f.depth = depth.data();
			f.overlay = overlay.data();
			current = f;
			lastPublished = published;
			lastPublishedAt = now;
			break;
		}
	}

	if (current.serial == 0 || now - lastPublishedAt > STALE_NANOS)
		return false;
	frame = current;
	return true;
}

//==========================================================================
//
// Crash log
//
//==========================================================================

#include <dbghelp.h>
#include <cstdio>
#pragma comment(lib, "dbghelp.lib")

namespace
{
	LONG logged;

	LONG CALLBACK crashLog(EXCEPTION_POINTERS *info)
	{
		const DWORD code = info->ExceptionRecord->ExceptionCode;
		if (code != EXCEPTION_ACCESS_VIOLATION && code != EXCEPTION_STACK_OVERFLOW && code != EXCEPTION_ILLEGAL_INSTRUCTION
			&& code != EXCEPTION_INT_DIVIDE_BY_ZERO && code != 0xC0000409)
			return EXCEPTION_CONTINUE_SEARCH;
		if (InterlockedIncrement(&logged) > 3)
			return EXCEPTION_CONTINUE_SEARCH;

		FILE *f = fopen("mc-crash.log", "a");
		if (f == nullptr)
			return EXCEPTION_CONTINUE_SEARCH;
		SYSTEMTIME t;
		GetLocalTime(&t);
		fprintf(f, "\n%02d:%02d:%02d exception %08lX at %p (thread %lu)", t.wHour, t.wMinute, t.wSecond, code,
			info->ExceptionRecord->ExceptionAddress, GetCurrentThreadId());
		if (code == EXCEPTION_ACCESS_VIOLATION && info->ExceptionRecord->NumberParameters >= 2)
			fprintf(f, " %s %p", info->ExceptionRecord->ExceptionInformation[0] ? "writing" : "reading",
				reinterpret_cast<void *>(info->ExceptionRecord->ExceptionInformation[1]));
		fprintf(f, "\n");

		const HANDLE process = GetCurrentProcess();
		SymSetOptions(SYMOPT_LOAD_LINES | SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS);
		SymInitialize(process, nullptr, TRUE);
		CONTEXT context = *info->ContextRecord;
		STACKFRAME64 frame = {};
		frame.AddrPC.Offset = context.Rip;
		frame.AddrFrame.Offset = context.Rbp;
		frame.AddrStack.Offset = context.Rsp;
		frame.AddrPC.Mode = frame.AddrFrame.Mode = frame.AddrStack.Mode = AddrModeFlat;
		for (int i = 0; i < 48; i++)
		{
			if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, process, GetCurrentThread(), &frame, &context, nullptr,
				SymFunctionTableAccess64, SymGetModuleBase64, nullptr) || frame.AddrPC.Offset == 0)
				break;
			char buffer[sizeof(SYMBOL_INFO) + 512] = {};
			SYMBOL_INFO *symbol = reinterpret_cast<SYMBOL_INFO *>(buffer);
			symbol->SizeOfStruct = sizeof(SYMBOL_INFO);
			symbol->MaxNameLen = 511;
			DWORD64 displacement = 0;
			IMAGEHLP_LINE64 line = {};
			line.SizeOfStruct = sizeof(line);
			DWORD lineDisplacement = 0;
			const bool named = SymFromAddr(process, frame.AddrPC.Offset, &displacement, symbol) != FALSE;
			const bool lined = SymGetLineFromAddr64(process, frame.AddrPC.Offset, &lineDisplacement, &line) != FALSE;
			fprintf(f, "  %p %s", reinterpret_cast<void *>(frame.AddrPC.Offset), named ? symbol->Name : "?");
			if (lined)
				fprintf(f, "  [%s:%lu]", line.FileName, line.LineNumber);
			fprintf(f, "\n");
		}
		fclose(f);
		return EXCEPTION_CONTINUE_SEARCH;
	}
}

void McShm_InstallCrashLog()
{
	AddVectoredExceptionHandler(1, crashLog);
}
