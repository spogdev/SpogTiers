package com.spog.tiers.data;

/**
 * The Discord account a player has linked, as our backend reports it.
 *
 * <p>The id always resolves when there is one; the name may not. Naming a
 * snowflake needs a bot token, which lives on the server, and the server
 * answers with the id alone when its bot is down. So a linked account with no
 * name is a normal answer, not a broken one -- there is simply nothing worth
 * drawing for it.
 *
 * @param id the Discord snowflake, never null for a linked account
 * @param username their handle, or null when it could not be resolved
 * @param displayName the name they show, or null; falls back to the handle
 */
public record DiscordAccount(String id, String username, String displayName) {
	/**
	 * Discord's brand colour, which the mark and the name beside it share.
	 *
	 * <p>Kept here rather than in either drawer, because the nametag and the
	 * editor's preview both need it and a tag whose preview is a different
	 * colour from the tag is worse than no preview.
	 */
	public static final int BLURPLE = 0x5865F2;

	/** Stands in for "this player has linked nothing", so a miss can be cached. */
	public static final DiscordAccount NONE = new DiscordAccount(null, null, null);

	/** Whether there is a name here worth drawing. */
	public boolean named() {
		return label() != null;
	}

	/**
	 * What to draw for this account, or null when there is nothing.
	 *
	 * <p>The username, not the display name. A display name is whatever
	 * someone has set today and two people can share one; the username is the
	 * handle that identifies the account, which is what a tag showing "who is
	 * this" wants. The display name is only a fallback, for the rare account
	 * whose handle could not be read.
	 */
	public String label() {
		if (username != null && !username.isBlank()) {
			return username;
		}
		return displayName != null && !displayName.isBlank() ? displayName : null;
	}
}
