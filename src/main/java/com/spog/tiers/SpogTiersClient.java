package com.spog.tiers;

import com.mojang.blaze3d.platform.InputConstants;
import com.spog.tiers.client.gui.ProfileScreen;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.TierCache;
import com.spog.tiers.data.TierService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

public class SpogTiersClient implements ClientModInitializer {
	private static SpogTiersConfig config;
	private static TierCache cache;
	private static TierService service;

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID, "main"));

	/**
	 * Created eagerly: {@code OptionsMixin} appends it while Options is being
	 * constructed, which happens before {@link #onInitializeClient()} runs.
	 */
	private static final KeyMapping OPEN_PROFILE_KEY = new KeyMapping(
			"key.spogtiers.open_profile",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			CATEGORY);

	@Override
	public void onInitializeClient() {
		config = SpogTiersConfig.load();
		cache = new TierCache(config);
		service = new TierService(config, cache);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			service.tick();
			while (OPEN_PROFILE_KEY.consumeClick()) {
				openProfileForTarget(client);
			}
		});

		SpogTiers.LOGGER.info("SpogTiers initialised");
	}

	/**
	 * Opens the panel for whoever the player is looking at, falling back to the
	 * player themselves so the key always does something.
	 */
	private static void openProfileForTarget(Minecraft client) {
		if (client.player == null || client.screen != null) {
			return;
		}

		AbstractClientPlayer target = client.player;
		HitResult hit = client.hitResult;
		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			if (entity instanceof AbstractClientPlayer other) {
				target = other;
			}
		}

		client.setScreen(new ProfileScreen(
				target.getUUID(),
				target.getGameProfile().name(),
				target));
	}

	public static KeyMapping openProfileKey() {
		return OPEN_PROFILE_KEY;
	}

	public static SpogTiersConfig config() {
		return config;
	}

	public static TierCache cache() {
		return cache;
	}

	public static TierService service() {
		return service;
	}
}
