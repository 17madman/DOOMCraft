package dev.rehan.passthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * The host's own menu is up (its pause screen): the HUD stays out of its way, and where the host's menu shows its
 * player, Steve is drawn instead, as the inventory screen draws him.
 * {"t":"doll","on":bool,"r":[left, top, right, bottom]} with the rectangle in fractions of the picture (optional).
 */
public final class Doll {
	private static volatile boolean on;
	private static volatile float[] rect;

	private Doll() {
	}

	static void update(final JsonObject m) {
		float[] r = null;
		if (m.has("r")) {
			JsonArray a = m.getAsJsonArray("r");
			r = new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
		}

		rect = r;
		on = m.get("on").getAsBoolean();
	}

	/** True: this replaces the HUD for the frame. */
	public static boolean extract(final GuiGraphicsExtractor graphics) {
		if (!Passthrough.active || !on) {
			return false;
		}

		float[] r = rect;
		LocalPlayer player = Minecraft.getInstance().player;
		if (r != null && player != null) {
			int w = graphics.guiWidth(), h = graphics.guiHeight();
			// the picture-in-picture texture is made at this size: a rectangle off the screen or absurdly large (the
			// host's page seen edge-on while it turns) must never get that far
			boolean sane = true;
			for (float f : r) {
				sane &= Float.isFinite(f) && f >= 0.0F && f <= 1.0F;
			}

			int x0 = sane ? Math.round(r[0] * w) : 0, y0 = sane ? Math.round(r[1] * h) : 0;
			int x1 = sane ? Math.round(r[2] * w) : 0, y1 = sane ? Math.round(r[3] * h) : 0;
			if (sane && x1 - x0 >= 8 && y1 - y0 >= 8 && x1 - x0 <= w / 2 && y1 - y0 <= h) {
				// the inventory's own box is 49 x 70 with a size of 30; he looks a little to his side, like there
				int size = Math.max(4, Math.round((y1 - y0) * 0.40F));
				InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x0, y0, x1, y1, size, 0.0625F,
					(x0 + x1) * 0.5F - (x1 - x0) * 0.4F, y0 + (y1 - y0) * 0.3F, player);
			}
		}

		return true;
	}
}
