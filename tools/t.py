"""Drives the background test run started by tools\\go.ps1 and looks at it.

    python tools\\t.py <shot-name|-> <console command> [<console command> ...]

The commands are GZDoom's own, carried out in turn ("+forward", "wait 70" (tics, 35 a second), "-forward",
"mc_turn 90 0", "mc_key attack 1", "mc_cmd give @a minecraft:tnt", "mc_state"). Then, unless the name is "-", a
screenshot is taken and written to shots\\<name>.png at a third of its size. What mc_state wrote since is printed.
"""
import glob, os, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN = os.path.join(ROOT, 'run')
LOG = os.path.join(RUN, 'mc-test.log')
SHOTS = RUN  # (an explicit file name is written to the working folder)


def main():
    name, commands = sys.argv[1], list(sys.argv[2:])
    before = os.path.getsize(LOG) if os.path.exists(LOG) else 0
    tics = sum(int(c.split()[1]) for c in commands if c.startswith('wait '))
    raw = os.path.join(SHOTS, 'test-shot.png')
    if name != '-':
        if os.path.exists(raw):
            os.remove(raw)
        commands.append('screenshot test-shot.png')
    script = os.path.join(RUN, 'mc-test.txt')
    while os.path.exists(script):
        time.sleep(0.05)
    with open(script + '.new', 'w') as f:
        f.write('\n'.join(commands) + '\n')
    os.replace(script + '.new', script)
    deadline = time.time() + tics / 35.0 + 20
    time.sleep(tics / 35.0 + 0.3)
    while time.time() < deadline and name != '-' and not glob.glob(os.path.join(SHOTS, 'test-shot*')):
        time.sleep(0.1)
    time.sleep(0.4)
    found = glob.glob(os.path.join(SHOTS, 'test-shot*'))
    if name != '-' and found:
        from PIL import Image
        im = Image.open(found[0])
        im.load()
        im.convert('RGB').resize((853, int(im.height * 853.0 / im.width))).save(os.path.join(ROOT, 'shots', name + '.png'))
        for f in found:
            os.remove(f)
    elif name != '-':
        print('no picture came')
    if os.path.exists(LOG):
        with open(LOG, 'rb') as f:
            f.seek(before)
            print(f.read().decode('utf-8', 'replace').strip()[:3000])


if __name__ == '__main__':
    os.makedirs(os.path.join(ROOT, 'shots'), exist_ok=True)
    main()
