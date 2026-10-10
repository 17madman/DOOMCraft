package dev.rehan.passthrough.client;

import java.util.UUID;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Who the player looks like under a host: classic Steve, unless the host asks for one of the skins that come with
 * the mod ({"t":"skin","k":"link"}: assets/passthrough/textures/entity/link.png, see tools/make_link_skin.py).
 */
public final class Skins {
	/** DefaultPlayerSkin picks floorMod(uuid.hashCode(), 18); index 15 is entity/player/wide/steve. */
	private static final UUID CLASSIC_STEVE = new UUID(0L, 15L);
	private static volatile String wanted = "";
	private static PlayerSkin link;

	private Skins() {
	}

	static void host(final String name) {
		wanted = name == null ? "" : name;
	}

	public static PlayerSkin current() {
		if (wanted.equals("link")) {
			if (link == null) {
				link = PlayerSkin.insecure(new ClientAsset.ResourceTexture(
					Identifier.fromNamespaceAndPath("passthrough", "entity/link"),
					Identifier.fromNamespaceAndPath("passthrough", "textures/entity/link.png")), null, null, PlayerModelType.WIDE);
			}

			return link;
		}

		return DefaultPlayerSkin.get(CLASSIC_STEVE);
	}
}
