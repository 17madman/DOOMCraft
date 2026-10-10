package dev.rehan.passthrough;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The host's ground as a height field: one height a block column, as the host sent it ("cols"). The blocks placed
 * from the same columns are what mobs stand on; the player, while Minecraft walks him, collides with this instead
 * (the way SkyCraft collides its player with the host's own surface): between column centres the height is
 * interpolated, so a slope is a slope and not a staircase of blocks he can end up inside.
 */
public final class Ground {
	/** Column key -> floor in eighths of a block. A column with nothing to stand on arrives as a very high floor (a wall). */
	private static final ConcurrentHashMap<Long, Integer> FLOOR8 = new ConcurrentHashMap<>();
	/** Columns whose heights differ by this much aren't a slope but a ledge or a wall: no interpolation across them. */
	private static final double LEDGE = 1.0;
	/** Ground this far over his feet is not ground he has sunk into: it is a wall he is inside of. */
	private static final double WALL = 2.5;

	private Ground() {
	}

	private static long key(final int x, final int z) {
		return ((long)x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** A column the host has looked at and found nothing in: known, with no ground (a pit beside the player). */
	private static final int PIT = Integer.MIN_VALUE;

	/**
	 * {x, z, floor8, ceil8, lo, hi, ...} as WorldBridge.columns takes them (lo = hi removes the column; with a floor
	 * of 1 it is a pit: known, nothing in it).
	 */
	static void columns(final int[] list) {
		for (int i = 0; i + 5 < list.length; i += 6) {
			if (list[i + 4] < list[i + 5]) {
				FLOOR8.put(key(list[i], list[i + 1]), list[i + 2]);
			} else if (list[i + 2] == 1) {
				FLOOR8.put(key(list[i], list[i + 1]), PIT);
			} else {
				FLOOR8.remove(key(list[i], list[i + 1]));
			}
		}
	}

	/** Has the host said anything of this column yet? */
	private static boolean known(final double x, final double z) {
		return FLOOR8.containsKey(key((int)Math.floor(x), (int)Math.floor(z)));
	}

	static void clear() {
		Walls.clear();
		FLOOR8.clear();
	}

	public static int size() {
		return FLOOR8.size();
	}

	private static double column(final int x, final int z) {
		Integer f = FLOOR8.get(key(x, z));
		return f == null || f == PIT ? Double.NaN : f / 8.0;
	}

	/** The ground's height at a point, or NaN where the host hasn't said. */
	public static double at(final double x, final double z) {
		double own = column((int)Math.floor(x), (int)Math.floor(z));
		if (Double.isNaN(own)) {
			return Double.NaN;
		}

		// heights are sampled at column centres
		double fx = x - 0.5, fz = z - 0.5;
		int x0 = (int)Math.floor(fx), z0 = (int)Math.floor(fz);
		double tx = fx - x0, tz = fz - z0;
		double h00 = corner(x0, z0, own), h10 = corner(x0 + 1, z0, own), h01 = corner(x0, z0 + 1, own), h11 = corner(x0 + 1, z0 + 1, own);
		return (h00 * (1.0 - tx) + h10 * tx) * (1.0 - tz) + (h01 * (1.0 - tx) + h11 * tx) * tz;
	}

	private static double corner(final int x, final int z, final double own) {
		double h = column(x, z);
		return Double.isNaN(h) || Math.abs(h - own) >= LEDGE ? own : h;
	}

	private static double ahead(final double x, final double z, final double here, final boolean strict) {
		return strict && !known(x, z) ? Double.POSITIVE_INFINITY : at(x, z, here);
	}

	/** As {@link #at}, with a fallback where the host hasn't said (the ground just goes on level there). */
	public static double at(final double x, final double z, final double otherwise) {
		double h = at(x, z);
		return Double.isNaN(h) ? otherwise : h;
	}

	/**
	 * A movement of something standing at (x, y, z), collided with the ground: it doesn't go below it, steps of up to
	 * `step` are walked up, and anything higher stops it (sliding along where one axis is free).
	 * Returns {dx, dy, dz}, or null for "unchanged".
	 *
	 * <p>On foot ({@code strict}) a column the host hasn't described yet is not walked into, and not fallen through:
	 * the host looks at the ground around the player a few times a second, and what it hasn't reached yet is as likely
	 * a wall as a floor. (Walked into as if it were level ground, the player went through the walls of a room in its
	 * first second and fell out of the scene.) A pit is something else: the host has said there is nothing there.
	 */
	public static double[] collide(final double x, final double y, final double z, final double dx, final double dy, final double dz, final double step,
		final boolean strict) {
		// the host's own walls first (see Walls), then its ground
		double[] held = strict ? Walls.hold(x, z, dx, dz) : null;
		double[] r = ground(x, y, z, held != null ? held[0] : dx, dy, held != null ? held[1] : dz, step, strict);
		return r != null || held == null ? r : new double[] {held[0], dy, held[1]};
	}

	private static double[] ground(final double x, final double y, final double z, final double dx, final double dy, final double dz, final double step,
		final boolean strict) {
		double here = at(x, z);
		if (Double.isNaN(here)) {
			return strict && dy < 0.0 && !known(x, z) && !FLOOR8.isEmpty() ? new double[] {dx, 0.0, dz} : null;
		}

		if (here - y > WALL) {
			// He is in a column that is a wall to him (it became one around him, or the host put him there): he is not
			// lifted to the top of it. He stays at his height and may walk out, not further in.
			double mx = dx, mz = dz;
			if (ahead(x + mx, z + mz, here, strict) - y > step) {
				if (mx != 0.0 && ahead(x + mx, z, here, strict) - y <= step) {
					mz = 0.0;
				} else if (mz != 0.0 && ahead(x, z + mz, here, strict) - y <= step) {
					mx = 0.0;
				} else {
					mx = 0.0;
					mz = 0.0;
				}
			}

			return new double[] {mx, Math.max(dy, 0.0), mz};
		}

		double feet = Math.max(y, here);
		double mx = dx, mz = dz;
		double there = ahead(x + mx, z + mz, here, strict);
		if (there - feet > step) {
			double alongX = ahead(x + mx, z, here, strict), alongZ = ahead(x, z + mz, here, strict);
			if (mx != 0.0 && alongX - feet <= step) {
				mz = 0.0;
				there = alongX;
			} else if (mz != 0.0 && alongZ - feet <= step) {
				mx = 0.0;
				there = alongZ;
			} else {
				mx = 0.0;
				mz = 0.0;
				there = here;
			}
		}

		double my = dy;
		if (y + my < there) {
			my = there - y;
		}

		return mx == dx && my == dy && mz == dz ? null : new double[] {mx, my, mz};
	}
}
