package dev.rehan.passthrough;

/**
 * Walls of the host's that the height field ({@link Ground}) knows nothing of: one that stands between two bits of
 * floor (a fence, a railing, the side of a building in a town, a door that is shut). The host finds them: while
 * Minecraft walks the player, the host collides its own player with its own world at the place Minecraft's stands,
 * and where that pushes him back it says so ({"t":"wall","p":[x,z],"n":[nx,nz]}): a line through p that the player
 * is not to cross, n pointing to his side of it.
 *
 * <p>A line and not a correction of his position: the message is a frame or two late, and corrections that late add
 * up (each frame reports all of the overshoot there is, the earlier reports of it still on their way) and throw him
 * back from the wall, over and over. A line can be said as often as one likes.
 *
 * <p>The lines are kept while he is near where they were found, and for a moment after they were last said.
 */
public final class Walls {
	private static final int MAX = 4;
	/** How far along a line from where it was found it still holds (a wall ends: at a doorway, a corner). */
	private static final double REACH = 0.7;
	/** How long a line holds after the host last said it. */
	private static final long KEEP_NANOS = 1_000_000_000L;
	private static final double[][] LINES = new double[MAX][4];
	private static final long[] SAID = new long[MAX];

	private Walls() {
	}

	public static synchronized void wall(final double px, final double pz, final double nx, final double nz) {
		double length = Math.hypot(nx, nz);
		if (!(length > 1.0e-6) || !Double.isFinite(px) || !Double.isFinite(pz)) {
			return;
		}

		long now = System.nanoTime();
		int slot = 0;
		for (int i = 0; i < MAX; i++) {
			// the same wall again (much the same direction, close by): that line is moved
			if (SAID[i] != 0L && LINES[i][2] * nx / length + LINES[i][3] * nz / length > 0.9 && Math.hypot(LINES[i][0] - px, LINES[i][1] - pz) < 1.5) {
				slot = i;
				break;
			}

			if (SAID[i] < SAID[slot]) {
				slot = i;
			}
		}

		LINES[slot][0] = px;
		LINES[slot][1] = pz;
		LINES[slot][2] = nx / length;
		LINES[slot][3] = nz / length;
		SAID[slot] = now;
	}

	static synchronized void clear() {
		java.util.Arrays.fill(SAID, 0L);
	}

	/** A movement {dx, dz} from (x, z), held at the lines: null when it crosses none. */
	public static synchronized double[] hold(final double x, final double z, final double dx, final double dz) {
		long now = System.nanoTime();
		double mx = dx, mz = dz;
		boolean held = false;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < MAX; i++) {
				if (SAID[i] == 0L) {
					continue;
				}

				double[] l = LINES[i];
				double along = (x - l[0]) * -l[3] + (z - l[1]) * l[2];
				if (now - SAID[i] > KEEP_NANOS || Math.abs(along) > REACH) {
					if (now - SAID[i] > KEEP_NANOS) {
						SAID[i] = 0L;
					}

					continue;
				}

				// (he may be a little behind the line already: he is let back to it, not thrown to it)
				double before = (x - l[0]) * l[2] + (z - l[1]) * l[3];
				double after = (x + mx - l[0]) * l[2] + (z + mz - l[1]) * l[3];
				if (after < 0.0 && after < before) {
					double back = Math.min(0.0, before) - after;
					mx += l[2] * back;
					mz += l[3] * back;
					held = true;
				}
			}
		}

		return held ? new double[] {mx, mz} : null;
	}
}
