package dev.rehan.passthrough.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.client.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.sdl.SDLVideo;

/** Host input, applied on the client thread: the host window has the focus, so Minecraft never sees these itself. */
final class ClientInput {
	private ClientInput() {
	}

	/** The mouse button the host holds down over a screen, for dragging (a scroll bar, items spread over slots). */
	private static MouseButtonInfo held;

	static void handle(final Minecraft minecraft, final JsonObject m) {
		LocalPlayer player = minecraft.player;
		switch (m.get("t").getAsString()) {
			case "key" -> {
				String k = m.get("k").getAsString();
				boolean down = !m.has("down") || m.get("down").getAsBoolean();
				if (k.equals("debug")) {
					// F3
					if (down) {
						minecraft.debugEntries.toggleDebugOverlay();
					}

					return;
				}

				if (k.equals("escape")) {
					if (down && minecraft.gui.screen() != null) {
						minecraft.gui.screen().onClose();
					}

					return;
				}

				KeyMapping key = switch (k) {
					case "use" -> minecraft.options.keyUse;
					case "attack" -> minecraft.options.keyAttack;
					case "pick" -> minecraft.options.keyPickItem;
					case "inventory" -> minecraft.options.keyInventory;
					case "drop" -> minecraft.options.keyDrop;
					case "swap" -> minecraft.options.keySwapOffhand;
					case "chat" -> minecraft.options.keyChat;
					case "forward" -> minecraft.options.keyUp;
					case "back" -> minecraft.options.keyDown;
					case "left" -> minecraft.options.keyLeft;
					case "right" -> minecraft.options.keyRight;
					case "jump" -> minecraft.options.keyJump;
					case "sneak" -> minecraft.options.keyShift;
					case "sprint" -> minecraft.options.keySprint;
					default -> null;
				};
				if (k.equals("attack") && down && player != null
					&& BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath().endsWith("_sword")) {
					// a sword swing: the host hits what's in front of Steve in its own world
					Passthrough.events.accept("{\"t\":\"melee\"}");
				}

				if (k.equals("attack") && down && player != null && minecraft.gui.screen() == null) {
					// any swing, with the damage of what he holds: the host hits its own creatures with it
					// ("s": how many full-strength swings a second the item makes, for the host to weigh a hurried one by)
					Passthrough.events.accept(String.format(java.util.Locale.ROOT, "{\"t\":\"swing\",\"d\":%.2f,\"s\":%.2f}",
						dev.rehan.passthrough.WorldBridge.attackDamage(player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)),
						player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)));
				}

				if (key != null) {
					if (down && !key.isDown()) {
						KeyMappingAccessor access = (KeyMappingAccessor)key;
						access.passthrough$setClickCount(access.passthrough$getClickCount() + 1);
					}

					key.setDown(down);
				}
			}
			case "mouse" -> {
				// the cursor over an open screen (inventory, crafting table...), in window pixels
				if (minecraft.gui.screen() != null) {
					double x = m.get("x").getAsDouble(), y = m.get("y").getAsDouble();
					double dx = x - minecraft.mouseHandler.xpos(), dy = y - minecraft.mouseHandler.ypos();
					minecraft.mouseHandler.onMove(minecraft.getWindow().handle(), x, y, 0.0, 0.0);
					// Minecraft only tells a screen of a drag while its own window has the focus, and under a host it
					// never has: a scroll bar could not be pulled, nor items spread by dragging. The screen is told here.
					if (held != null && !minecraft.isWindowActive() && (dx != 0.0 || dy != 0.0) && minecraft.gui.screen() != null) {
						var window = minecraft.getWindow();
						minecraft.gui.screen().mouseDragged(
							new net.minecraft.client.input.MouseButtonEvent(minecraft.mouseHandler.getScaledXPos(window),
								minecraft.mouseHandler.getScaledYPos(window), held),
							net.minecraft.client.MouseHandler.getScaledXPos(window, dx), net.minecraft.client.MouseHandler.getScaledYPos(window, dy));
					}
				} else {
					held = null;
				}
			}
			case "click" -> {
				if (minecraft.gui.screen() != null) {
					// the host says 0 = left, 1 = right, 2 = middle; Minecraft 26.3 counts like SDL: left 1, middle 2, right 3
					int b = switch (m.get("b").getAsInt()) {
						case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
						case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
						default -> InputConstants.MOUSE_BUTTON_MIDDLE;
					};
					MouseButtonInfo button = new MouseButtonInfo(b, m.has("mods") && m.get("mods").getAsInt() != 0 ? InputConstants.MOD_SHIFT : 0);
					boolean down = m.get("down").getAsBoolean();
					held = down ? button : held != null && held.button() == b ? null : held;
					minecraft.mouseHandler.onButton(minecraft.getWindow().handle(), button, down ? 1 : 0);
				}
			}
			case "slot" -> {
				if (player != null) {
					player.getInventory().setSelectedSlot(Math.clamp(m.get("n").getAsInt(), 0, Inventory.getSelectionSize() - 1));
				}
			}
			case "scroll" -> {
				if (minecraft.gui.screen() != null) {
					// over a screen the wheel is the screen's (the creative list, a villager's trades)
					minecraft.mouseHandler.onScroll(minecraft.getWindow().handle(), 0.0, m.get("d").getAsDouble());
				} else if (player != null) {
					Inventory inventory = player.getInventory();
					int size = Inventory.getSelectionSize();
					inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() - m.get("d").getAsInt(), size));
				}
			}
			case "char" -> {
				// typing into an open screen (chat): the host has the keyboard
				if (minecraft.gui.screen() != null) {
					// As Minecraft has it from a real keyboard: the key first (E shuts an inventory, a number swaps
					// with the hotbar, a focused text box takes it for itself), and then the letter it types.
					var screen = minecraft.gui.screen();
					int c = m.get("c").getAsInt();
					int lower = Character.toLowerCase(c);
					String name = lower == ' ' ? "space" : lower >= 'a' && lower <= 'z' || lower >= '0' && lower <= '9' ? String.valueOf((char)lower) : null;
					if (name != null) {
						int mods = Character.isUpperCase(c) ? InputConstants.MOD_SHIFT : 0;
						screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.getKey("key.keyboard." + name).getValue(), lower, mods));
					}

					if (minecraft.gui.screen() == screen) {
						screen.charTyped(new net.minecraft.client.input.CharacterEvent(c));
					}
				}
			}
			case "keypress" -> {
				if (minecraft.gui.screen() != null) {
					String name = m.get("k").getAsString();
					int key = switch (name) {
						case "enter" -> InputConstants.KEY_RETURN;
						case "backspace" -> InputConstants.KEY_BACKSPACE;
						case "delete" -> InputConstants.KEY_DELETE;
						case "left" -> InputConstants.KEY_LEFT;
						case "right" -> InputConstants.KEY_RIGHT;
						case "up" -> InputConstants.KEY_UP;
						case "down" -> InputConstants.KEY_DOWN;
						case "home" -> InputConstants.KEY_HOME;
						case "end" -> InputConstants.KEY_END;
						case "tab" -> InputConstants.KEY_TAB;
						default -> InputConstants.KEY_ESCAPE;
					};
					// A key event carries two numbers: where the key is on the keyboard (the constants above) and what
					// the key means (SDL's key code). Text boxes go by the second for editing keys; with it left at 0,
					// as it was at first, Backspace, Delete and the arrows did nothing.
					int keycode = switch (name) {
						case "enter" -> 13;
						case "backspace" -> 8;
						case "delete" -> 127;
						case "tab" -> 9;
						case "home" -> 0x4000004A;
						case "end" -> 0x4000004D;
						case "right" -> 0x4000004F;
						case "left" -> 0x40000050;
						case "down" -> 0x40000051;
						case "up" -> 0x40000052;
						default -> 27;
					};
					minecraft.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(key, keycode, 0));
				}
			}
			case "doll" -> Doll.update(m);
			case "hud" -> {
				if (minecraft.gui.hud.isHidden() != m.get("hidden").getAsBoolean()) {
					minecraft.gui.hud.toggle();
				}
			}
			case "view" -> {
				// match the host's picture exactly: un-minimize/un-maximize first (resizing a maximized window is ignored)
				int w = m.get("w").getAsInt(), h = m.get("h").getAsInt();
				long handle = minecraft.getWindow().handle();
				SDLVideo.SDL_RestoreWindow(handle);
				minecraft.getWindow().setWindowed(w, h);
				SDLVideo.SDL_SetWindowSize(handle, w, h);
				SDLVideo.SDL_SyncWindow(handle);
				// restoring it to resize it also shows it: under a host it stays hidden
				if (PassthroughClient.windowHidden) {
					SDLVideo.SDL_HideWindow(handle);
				}
			}
			default -> {
			}
		}
	}
}
