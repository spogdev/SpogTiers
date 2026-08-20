package com.spog.tiers.data;

/**
 * The tier lists SpogTiers can pull from.
 *
 * <p>Endpoints were verified live; none of these services publish API docs, so
 * treat the paths here as observed behaviour rather than a contract. The first
 * three share one JSON schema; PVPHQ has its own (see {@code TierService}).
 */
public enum TierList {
	PVPHQ("pvphq", "PVPHQ Ranked", "https://pvphq.com/api/v1/players/", true),
	PVPTIERS("pvptiers", "PvPTiers", "https://pvptiers.com/api/profile/", false),
	SUBTIERS("subtiers", "SubTiers", "https://subtiers.net/api/profile/", false),
	MCTIERS("mctiers", "MCTiers", "https://mctiers.com/api/profile/", false);

	private final String key;
	private final String displayName;
	private final String endpoint;
	private final boolean dashedUuid;

	TierList(String key, String displayName, String endpoint, boolean dashedUuid) {
		this.key = key;
		this.displayName = displayName;
		this.endpoint = endpoint;
		this.dashedUuid = dashedUuid;
	}

	public String key() {
		return key;
	}

	public String displayName() {
		return displayName;
	}

	public String endpoint() {
		return endpoint;
	}

	/** PVPHQ rejects undashed UUIDs with a 400; the others expect them undashed. */
	public boolean usesDashedUuid() {
		return dashedUuid;
	}

	/** PVPHQ returns a different payload shape and needs its own parser. */
	public boolean isPvpHq() {
		return this == PVPHQ;
	}
}
