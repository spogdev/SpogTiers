package com.spog.tiers.data;

import com.spog.tiers.config.SpogTiersConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store of looked-up tiers. The network thread writes, the render
 * thread reads, so every access goes through a concurrent map and reads never
 * block.
 */
public class TierCache {
	private final SpogTiersConfig config;
	private final Map<UUID, PlayerTiers> entries = new ConcurrentHashMap<>();
	private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
	private final Map<UUID, Long> failedUntil = new ConcurrentHashMap<>();

	public TierCache(SpogTiersConfig config) {
		this.config = config;
	}

	public PlayerTiers get(UUID uuid) {
		PlayerTiers entry = entries.get(uuid);
		if (entry == null) {
			return null;
		}
		if (isExpired(entry)) {
			entries.remove(uuid);
			return null;
		}
		return entry;
	}

	public void put(UUID uuid, PlayerTiers tiers) {
		entries.put(uuid, tiers);
		pending.remove(uuid);
		failedUntil.remove(uuid);
	}

	/** Records a failed lookup so we back off instead of hammering the API. */
	public void markFailed(UUID uuid, long backoffMillis) {
		pending.remove(uuid);
		failedUntil.put(uuid, System.currentTimeMillis() + backoffMillis);
	}

	public boolean needsLookup(UUID uuid) {
		if (pending.containsKey(uuid)) {
			return false;
		}
		Long until = failedUntil.get(uuid);
		if (until != null) {
			if (System.currentTimeMillis() < until) {
				return false;
			}
			failedUntil.remove(uuid);
		}
		return get(uuid) == null;
	}

	public void markPending(UUID uuid) {
		pending.put(uuid, System.currentTimeMillis());
	}

	private boolean isExpired(PlayerTiers entry) {
		long age = System.currentTimeMillis() - entry.fetchedAtMillis();
		return age > config.cacheTtlSeconds * 1000L;
	}

	public void clear() {
		entries.clear();
		pending.clear();
		failedUntil.clear();
	}

	public int size() {
		return entries.size();
	}
}
