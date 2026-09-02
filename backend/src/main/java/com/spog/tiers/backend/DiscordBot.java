package com.spog.tiers.backend;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Door SMP grading bot.
 *
 * <p><b>Authorization is re-checked inside every write handler</b> against
 * {@link GraderStore}, rather than trusted from wherever the command came from.
 * An unauthorised caller gets the same refusal whether or not the bot has any
 * graders configured, so probing tells them nothing.
 *
 * <p>Failures reply ephemerally -- only the caller sees them -- so a mistyped
 * name does not litter the channel.
 */
public final class DiscordBot extends ListenerAdapter {
	private static final Logger LOG = LoggerFactory.getLogger(DiscordBot.class);

	/** The tierlist's name, as players see it. */
	public static final String LIST_NAME = "Door SMP";

	/** Shown when a lookup finds nobody. Neutral, not alarming: most players are ungraded. */
	private static final Color NEUTRAL = new Color(0x9AA5B1);

	private final GradeStore grades;
	private final GraderStore graders;
	private final MojangNames names;
	private final TierlistImage image = new TierlistImage();

	public DiscordBot(GradeStore grades, GraderStore graders, MojangNames names) {
		this.grades = grades;
		this.graders = graders;
		this.names = names;
	}

	/**
	 * Log in and register commands.
	 *
	 * <p>No privileged intents: the bot only ever reacts to its own slash
	 * commands, so it needs neither message content nor member lists.
	 */
	public JDA start(String token) throws InterruptedException {
		// No intents at all: the bot only ever reacts to its own slash commands,
		// so it needs neither message content nor member lists. Guild caching is
		// left on deliberately -- createLight() would disable it, and commands
		// are registered per guild, which needs getGuilds() to be populated.
		JDA jda = JDABuilder.createDefault(token)
				.enableIntents(java.util.Collections.emptyList())
				.setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.NONE)
				.disableCache(java.util.EnumSet.allOf(
						net.dv8tion.jda.api.utils.cache.CacheFlag.class))
				.addEventListeners(this)
				.build();
		jda.awaitReady();
		return jda;
	}

	@Override
	public void onReady(@NotNull ReadyEvent event) {
		LOG.info("logged in as {}", event.getJDA().getSelfUser().getName());

		// Register per guild, not globally. Global commands take up to an hour
		// to propagate, which looks exactly like a broken bot; a guild's appear
		// at once. The bot is invited to a handful of servers at most, so there
		// is nothing to gain from the global route.
		// Global registration is what makes the commands user-installable: a
		// guild-scoped command belongs to that server and cannot travel with a
		// person's account. The cost is propagation, which Discord takes up to
		// an hour over.
		event.getJDA().updateCommands().addCommands(commands()).queue(
				ok -> LOG.info("registered {} command(s) globally; user installs "
						+ "and new servers pick these up, which can take up to "
						+ "an hour to propagate", ok.size()),
				error -> LOG.error("could not register global commands", error));

		// Servers the bot is actually in also get a guild-scoped copy, which
		// appears at once. Discord shows a guild command in place of the global
		// one of the same name rather than both, so this is a head start rather
		// than a duplicate.
		for (Guild guild : event.getJDA().getGuilds()) {
			register(guild);
		}
	}

	/** A server that invited the bot while it was already running. */
	@Override
	public void onGuildJoin(@NotNull GuildJoinEvent event) {
		LOG.info("joined {}", event.getGuild().getName());
		register(event.getGuild());
	}

	/** Publish the command set to one guild, for immediate availability. */
	private void register(Guild guild) {
		List<SlashCommandData> commands = commands();
		guild.updateCommands().addCommands(commands).queue(
				ok -> LOG.info("registered {} command(s) in {}",
						commands.size(), guild.getName()),
				error -> LOG.error("could not register commands in {}",
						guild.getName(), error));
	}

	/** The command set. Built fresh each time; JDA's builders are not reusable. */
	private List<SlashCommandData> commands() {
		OptionData player = new OptionData(OptionType.STRING, "player",
				"The Minecraft username", true);

		// A choice list rather than free text: an invalid grade becomes
		// unrepresentable, and graders get a picker instead of having to
		// remember the ladder.
		OptionData grade = new OptionData(OptionType.STRING, "grade",
				"The tier to assign", true);
		for (Grade value : Grade.values()) {
			grade.addChoice(value.label(), value.label());
		}

		// Positive is up, negative is down, matching how the move reads aloud:
		// "bump them up two" is 2.
		OptionData places = new OptionData(OptionType.INTEGER, "places",
				"Places to move: 1 is up one, -1 is down one", true);

		// Optional so the bare command retires; pass false to bring someone back.
		OptionData retired = new OptionData(OptionType.BOOLEAN, "retired",
				"False brings them out of retirement", false);

		OptionData filter = new OptionData(OptionType.STRING, "grade",
				"Only show this tier", false);
		for (Grade value : Grade.values()) {
			filter.addChoice(value.label(), value.label());
		}

		// Installable either to a server or to a person's own account, and
		// usable in servers, the bot's DMs, and group chats. A user who adds
		// the app carries these commands into any server they are in, whether
		// or not the bot itself is there.
		List<SlashCommandData> commands = List.of(
				Commands.slash("settier", "Set a player's " + LIST_NAME + " tier")
						.addOptions(player, grade),
				Commands.slash("removetier", "Remove a player's " + LIST_NAME + " tier")
						.addOptions(player),
				Commands.slash("tier", "Look up a player's " + LIST_NAME + " tier")
						.addOptions(player),
				Commands.slash("tierlist", "Show the whole tierlist")
						.addOptions(filter),
				Commands.slash("bump", "Move a player within their tier")
						.addOptions(player, places),
				Commands.slash("retire", "Retire a player, or bring them back")
						.addOptions(player, retired));

		for (SlashCommandData command : commands) {
			command.setIntegrationTypes(IntegrationType.ALL)
					.setContexts(InteractionContextType.ALL);
		}
		return commands;
	}

	@Override
	public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
		switch (event.getName()) {
			case "settier" -> setTier(event);
			case "removetier" -> removeTier(event);
			case "tier" -> lookup(event);
			case "tierlist" -> list(event);
			case "bump" -> bump(event);
			case "retire" -> retire(event);
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	private void setTier(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		Grade grade = Grade.parse(event.getOption("grade", "", OptionMapping::getAsString));
		if (grade == null) {
			event.reply("That is not a valid tier").setEphemeral(true).queue();
			return;
		}

		// Mojang can be slow; acknowledge first so the interaction cannot expire.
		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		String current = names.nameFor(id);
		GradeStore.Record previous = grades.set(id, current == null ? name : current, grade,
				event.getUser().getName(), event.getUser().getId());

		String shown = current == null ? name : current;
		String message = "Set **" + shown + "** tier to **" + grade.label() + "**";
		LOG.info("{} set {} ({}) to {}", event.getUser().getName(), shown, id, grade.label());
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription(message)
				.setColor(new Color(grade.color()))
				.build()).queue();
	}

	private void removeTier(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		GradeStore.Record previous = grades.remove(id);
		if (previous == null) {
			event.getHook().sendMessage("**" + name + "** is not on the tierlist")
					.setEphemeral(true).queue();
			return;
		}
		String shown = previous.name().isEmpty() ? name : previous.name();
		LOG.info("{} removed {} from the tierlist (was {})", event.getUser().getName(),
				shown, previous.grade().label());
		// Coloured with the tier they held, so the message still says which.
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription("Removed **" + shown + "** from tierlist")
				.setColor(new Color(previous.grade().color()))
				.build()).queue();
	}

	private void lookup(SlashCommandInteractionEvent event) {
		String name = event.getOption("player", "", OptionMapping::getAsString);
		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		// Keep the stored name current while we happen to know it.
		String current = names.nameFor(id);
		if (current != null) {
			grades.refreshName(id, current);
		}
		String shown = current == null ? name : current;

		GradeStore.Record record = grades.get(id);
		if (record == null) {
			event.getHook().sendMessageEmbeds(new EmbedBuilder()
					.setTitle(shown)
					.setThumbnail(head(id))
					.setDescription("Not on the " + LIST_NAME + " tierlist yet")
					.setColor(NEUTRAL)
					.build()).queue();
			return;
		}

		EmbedBuilder embed = new EmbedBuilder()
				.setTitle(shown)
				.setThumbnail(head(id))
				.setColor(new Color(record.grade().color()))
				.addField(LIST_NAME + " Tierlist",
						"**" + (record.retired() ? "R" : "") + record.grade().label() + "**"
								+ (record.retired() ? "  *(retired)*" : ""), false);
		if (record.gradedBy() != null && !record.gradedBy().isBlank()) {
			// <t:unix:R> renders as "3 days ago" in each reader's own locale.
			embed.setFooter("Graded by " + record.gradedBy());
			embed.addField("Graded", "<t:" + record.gradedAt() + ":R>", false);
		}
		event.getHook().sendMessageEmbeds(embed.build()).queue();
	}

	private void list(SlashCommandInteractionEvent event) {
		Grade filter = Grade.parse(event.getOption("grade", "", OptionMapping::getAsString));

		// all() is already sorted best tier first, then by name, so grouping is
		// just a walk: a LinkedHashMap keeps the tiers in that same order.
		Map<Grade, List<GradeStore.Record>> byTier = new LinkedHashMap<>();
		int total = 0;
		for (GradeStore.Record record : grades.all()) {
			// Retired players keep their tier and can still be looked up one by
			// one, but the picture is about who is currently ranked.
			if (record.retired() || (filter != null && record.grade() != filter)) {
				continue;
			}
			byTier.computeIfAbsent(record.grade(), k -> new ArrayList<>()).add(record);
			total++;
		}

		if (total == 0) {
			event.reply(filter == null
					? "Nobody is on the tierlist yet"
					: "Nobody is **" + filter.label() + "**").queue();
			return;
		}

		// Every tier gets a row, even an empty one, so the picture shows the
		// whole ladder rather than only the rungs that happen to be occupied.
		// Filtered to one tier, only that row is drawn.
		List<Map.Entry<Grade, List<GradeStore.Record>>> rows = new ArrayList<>();
		for (Grade tier : Grade.values()) {
			if (filter != null && tier != filter) {
				continue;
			}
			rows.add(Map.entry(tier, byTier.getOrDefault(tier, List.of())));
		}

		// Faces are fetched over the network, so acknowledge first: rendering
		// can outlast the three seconds Discord allows for a reply.
		event.deferReply().queue();

		byte[] png = image.render(rows);
		if (png == null) {
			// Rendering is a nicety; the standing itself is the point, so fall
			// back to text rather than failing the command.
			event.getHook().sendMessageEmbeds(textList(filter, rows, total)).queue();
			return;
		}
		event.getHook()
				.sendFiles(FileUpload.fromData(png, "tierlist.png"))
				.queue();
	}

	/** The listing as text, for when the image cannot be drawn. */
	private MessageEmbed textList(Grade filter,
			List<Map.Entry<Grade, List<GradeStore.Record>>> rows, int total) {
		StringBuilder body = new StringBuilder();
		int shown = 0;
		for (Map.Entry<Grade, List<GradeStore.Record>> entry : rows) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			StringBuilder block = new StringBuilder();
			if (body.length() > 0) {
				block.append('\n');
			}
			block.append("**").append(entry.getKey().label()).append("**\n");
			for (GradeStore.Record record : entry.getValue()) {
				block.append("- ").append(record.name()).append('\n');
			}
			if (body.length() + block.length() > 3900) {
				break;
			}
			body.append(block);
			shown += entry.getValue().size();
		}
		if (shown < total) {
			body.append("\n_...and ").append(total - shown).append(" more._");
		}

		return new EmbedBuilder()
				.setTitle(LIST_NAME + " Tierlist"
						+ (filter == null ? "" : " -- " + filter.label()))
				.setDescription(body.toString())
				.setColor(filter == null ? NEUTRAL : new Color(filter.color()))
				.setFooter(total + " player" + (total == 1 ? "" : "s"))
				.build();
	}

	private void bump(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		int places = event.getOption("places", 0L, OptionMapping::getAsLong).intValue();
		if (places == 0) {
			event.reply("Give a number of places: 1 moves up one, -1 moves down one")
					.setEphemeral(true).queue();
			return;
		}

		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		int moved = grades.bump(id, places);
		if (moved < 0) {
			event.getHook().sendMessage("**" + name + "** is not on the tierlist")
					.setEphemeral(true).queue();
			return;
		}

		GradeStore.Record record = grades.get(id);
		String shown = record.name().isEmpty() ? name : record.name();
		if (moved == 0) {
			event.getHook().sendMessage("**" + shown + "** is already at the "
					+ (places > 0 ? "top" : "bottom") + " of **"
					+ record.grade().label() + "**").queue();
			return;
		}

		LOG.info("{} bumped {} by {} in {}", event.getUser().getName(), shown, moved,
				record.grade().label());
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription("Moved **" + shown + "** "
						+ (moved > 0 ? "up " : "down ") + Math.abs(moved)
						+ (Math.abs(moved) == 1 ? " place" : " places")
						+ " in **" + record.grade().label() + "**")
				.setColor(new Color(record.grade().color()))
				.build()).queue();
	}

	private void retire(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		boolean retired = event.getOption("retired", true, OptionMapping::getAsBoolean);

		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		GradeStore.Record record = grades.get(id);
		if (record == null) {
			event.getHook().sendMessage("**" + name + "** is not on the tierlist")
					.setEphemeral(true).queue();
			return;
		}
		String shown = record.name().isEmpty() ? name : record.name();

		if (!grades.retire(id, retired)) {
			event.getHook().sendMessage("**" + shown + "** is already "
					+ (retired ? "retired" : "active")).setEphemeral(true).queue();
			return;
		}

		LOG.info("{} {} {}", event.getUser().getName(),
				retired ? "retired" : "un-retired", shown);
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription(retired
						? "Retired **" + shown + "** at **R" + record.grade().label() + "**"
						: "Brought **" + shown + "** back at **"
								+ record.grade().label() + "**")
				.setColor(new Color(record.grade().color()))
				.build()).queue();
	}

	/**
	 * Check the caller may grade, refusing ephemerally if not.
	 *
	 * @return true if the command should go ahead
	 */
	private boolean authorised(SlashCommandInteractionEvent event) {
		if (graders.isGrader(event.getUser().getId())) {
			return true;
		}
		// Identical whether or not graders are configured: a caller learns
		// nothing about the setup from being refused.
		event.reply("You do not have permission to set tiers").setEphemeral(true).queue();
		return false;
	}

	/**
	 * Resolve a name to a UUID, replying with the reason if it cannot be done.
	 *
	 * @return the UUID, or null if the caller has already been answered
	 */
	private UUID resolve(SlashCommandInteractionEvent event, String name) {
		try {
			UUID id = names.idFor(name);
			if (id == null) {
				event.getHook().sendMessage("No Minecraft account called **" + name + "**")
						.setEphemeral(true).queue();
				return null;
			}
			return id;
		} catch (MojangNames.Unavailable e) {
			LOG.warn("mojang lookup for '{}' failed ({})", name, e.toString());
			event.getHook().sendMessage("Could not reach Mojang to look up **" + name
					+ "**. Try again in a moment.").setEphemeral(true).queue();
			return null;
		}
	}

	/**
	 * The player's face, for the embed thumbnail.
	 *
	 * <p>Undashed and with no query string on purpose: minotar 301-redirects a
	 * dashed UUID, and Discord's image proxy is unreliable with redirects, so
	 * the thumbnail silently fails to load.
	 */
	private static String head(UUID id) {
		return "https://minotar.net/helm/" + id.toString().replace("-", "") + "/128.png";
	}
}
