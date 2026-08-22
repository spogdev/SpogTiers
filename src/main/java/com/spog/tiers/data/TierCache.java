package com.spog.tiers.data;

import com.spog.tiers.config.SpogTiersConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store of looked-up tiers, keyed by player and then by tier list.
 * The network threads write and the render thread reads, so every access goes
 * through concurrent maps and reads never block.
 */
public class TierCache {
	private final SpogTiersConfig config;
	private final Map<UUID, Map<TierList, PlayerTiers>> entries = new ConcurrentHashMap<>();
	private final Map<UUID, Long> completedAt = new ConcurrentHashMap<>();
	private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
	private final Map<UUID, Long> failedUntil = new ConcurrentHashMap<>();

	public TierCache(SpogTiersConfig config) {
		this.config = config;
	}

	/** One list's data for a player, or null when absent or stale. */
	public PlayerTiers get(UUID uuid, TierList list) {
		if (isExpired(uuid)) {
			invalidate(uuid);
			return null;
		}
		Map<TierList, PlayerTiers> byList = entries.get(uuid);
		return byList == null ? null : byList.get(list);
	}

	/** Data from the list the user is currently displaying. */
	public PlayerTiers get(UUID uuid) {
		return get(uuid, config.displayList);
	}

	/** Every list we have data for, in enum order. */
	public Map<TierList, PlayerTiers> allLists(UUID uuid) {
		if (isExpired(uuid)) {
			invalidate(uuid);
			return Map.of();
		}
		Map<TierList, PlayerTiers> byList = entries.get(uuid);
		return byList == null ? Map.of() : byList;
	}

	public void put(UUID uuid, TierList list, PlayerTiers tiers) {
		entries.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>(new EnumMap<>(TierList.class)))
				.put(list, tiers);
	}

	public void markComplete(UUID uuid) {
		completedAt.put(uuid, System.currentTimeMillis());
		pending.remove(uuid);
		failedUntil.remove(uuid);
	}

	/** Records a failed lookup so we back off instead of hammering the APIs. */
	public void markFailed(UUID uuid, long backoffMillis) {
		pending.remove(uuid);
		failedUntil.put(uuid, System.currentTimeMillis() + backoffMillis);
	}

	public void markPending(UUID uuid) {
		pending.put(uuid, System.currentTimeMillis());
	}

	public boolean isPending(UUID uuid) {
		return pending.containsKey(uuid);
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
		return !entries.containsKey(uuid) || isExpired(uuid);
	}

	public void invalidate(UUID uuid) {
		entries.remove(uuid);
		completedAt.remove(uuid);
		pending.remove(uuid);
		failedUntil.remove(uuid);
	}

	private boolean isExpired(UUID uuid) {
		Long at = completedAt.get(uuid);
		if (at == null) {
			return false;
		}
		return System.currentTimeMillis() - at > config.cacheTtlSeconds * 1000L;
	}

	public void clear() {
		entries.clear();
		completedAt.clear();
		pending.clear();
		failedUntil.clear();
	}

	public int size() {
		return entries.size();
	}
}
