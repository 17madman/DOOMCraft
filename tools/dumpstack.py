"""dumpstack.py <minidump> <exe with pdb beside it>: exception + symbolised return addresses found on the crashing stack."""
import ctypes, struct, sys
from ctypes import wintypes

d = open(sys.argv[1], 'rb').read()
exe = sys.argv[2]
sig, ver, nstreams, dirrva = struct.unpack_from('<IIII', d, 0)
streams = {}
for i in range(nstreams):
    t, size, rva = struct.unpack_from('<III', d, dirrva + i * 12)
    streams[t] = (rva, size)

# modules
rva, _ = streams[4]
n, = struct.unpack_from('<I', d, rva)
mods = []
for i in range(n):
    base, size, _, _, namerva = struct.unpack_from('<QIIII', d, rva + 4 + i * 108)
    ln, = struct.unpack_from('<I', d, namerva)
    mods.append((base, size, d[namerva + 4:namerva + 4 + ln].decode('utf-16le')))
gz = next(m for m in mods if m[2].lower().endswith('gzdoom.exe'))

# exception
rva, _ = streams[6]
tid, _, code, flags, rec, addr, nparams, _ = struct.unpack_from('<IIIIQQII', d, rva)
params = struct.unpack_from('<%dQ' % nparams, d, rva + 8 + 32)
ctxsize, ctxrva = struct.unpack_from('<II', d, rva + 8 + 152)
rsp, = struct.unpack_from('<Q', d, ctxrva + 0x98)
rip, = struct.unpack_from('<Q', d, ctxrva + 0xF8)
print('exception %08X at %016X params %s' % (code, addr, [hex(p) for p in params]))

# memory: threads' stacks (stream 3) and memory lists
def read(addr, size):
    if 9 in streams:
        rva, _ = streams[9]
        n, base = struct.unpack_from('<QQ', d, rva)
        off = base
        for i in range(n):
            start, sz = struct.unpack_from('<QQ', d, rva + 16 + i * 16)
            if start <= addr < start + sz:
                return d[off + addr - start: off + min(addr - start + size, sz)]
            off += sz
    if 5 in streams:
        rva, _ = streams[5]
        n, = struct.unpack_from('<I', d, rva)
        for i in range(n):
            start, sz, mrva = struct.unpack_from('<QII', d, rva + 4 + i * 16)
            if start <= addr < start + sz:
                return d[mrva + addr - start: mrva + min(addr - start + size, sz)]
    return b''

dbghelp = ctypes.WinDLL('dbghelp')
k32 = ctypes.WinDLL('kernel32')
proc = wintypes.HANDLE(0x1234)
dbghelp.SymSetOptions(0x10 | 0x2 | 0x4)  # LOAD_LINES | UNDNAME | DEFERRED
dbghelp.SymInitialize(proc, None, False)
dbghelp.SymLoadModuleEx.restype = ctypes.c_uint64
dbghelp.SymLoadModuleEx.argtypes = [wintypes.HANDLE, wintypes.HANDLE, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint64, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]
dbghelp.SymLoadModuleEx(proc, None, exe.encode(), None, gz[0], gz[1], None, 0)

class SYMBOL_INFO(ctypes.Structure):
    _fields_ = [('SizeOfStruct', wintypes.ULONG), ('TypeIndex', wintypes.ULONG), ('Reserved', ctypes.c_uint64 * 2), ('Index', wintypes.ULONG),
                ('Size', wintypes.ULONG), ('ModBase', ctypes.c_uint64), ('Flags', wintypes.ULONG), ('Value', ctypes.c_uint64),
                ('Address', ctypes.c_uint64), ('Register', wintypes.ULONG), ('Scope', wintypes.ULONG), ('Tag', wintypes.ULONG),
                ('NameLen', wintypes.ULONG), ('MaxNameLen', wintypes.ULONG), ('Name', ctypes.c_char * 512)]
class LINE(ctypes.Structure):
    _fields_ = [('SizeOfStruct', wintypes.DWORD), ('Key', ctypes.c_void_p), ('LineNumber', wintypes.DWORD), ('FileName', ctypes.c_char_p), ('Address', ctypes.c_uint64)]
dbghelp.SymFromAddr.argtypes = [wintypes.HANDLE, ctypes.c_uint64, ctypes.POINTER(ctypes.c_uint64), ctypes.POINTER(SYMBOL_INFO)]
dbghelp.SymGetLineFromAddr64.argtypes = [wintypes.HANDLE, ctypes.c_uint64, ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(LINE)]

def sym(a):
    for m in mods:
        if m[0] <= a < m[0] + m[1]:
            name = m[2].split(chr(92))[-1]
            if m is not gz:
                return '%s+%X' % (name, a - m[0])
            s = SYMBOL_INFO(); s.SizeOfStruct = ctypes.sizeof(SYMBOL_INFO) - 512; s.MaxNameLen = 511
            disp = ctypes.c_uint64()
            out = '%s+%X' % (name, a - m[0])
            if dbghelp.SymFromAddr(proc, a, ctypes.byref(disp), ctypes.byref(s)):
                out = '%s+0x%X' % (s.Name.decode(errors='replace'), disp.value)
                l = LINE(); l.SizeOfStruct = ctypes.sizeof(LINE); dd = wintypes.DWORD()
                if dbghelp.SymGetLineFromAddr64(proc, a, ctypes.byref(dd), ctypes.byref(l)):
                    out += '  [%s:%d]' % (l.FileName.decode().split(chr(92)+'src'+chr(92))[-1], l.LineNumber)
            return out
    return None

print('rip', sym(rip) or hex(rip))
import os
back = int(os.environ.get('BACK','0'),0)
stack = read(rsp - back, 0x3000 + back)
shown = 0
for i in range(0, len(stack) - 7, 8):
    v, = struct.unpack_from('<Q', stack, i)
    if gz[0] <= v < gz[0] + gz[1]:
        print('  +%04X %s' % (i, sym(v)))
        shown += 1
        if shown > int(os.environ.get('MAX','28')):
            break
