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
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
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
		List<Guild> guilds = event.getJDA().getGuilds();
		if (guilds.isEmpty()) {
			LOG.warn("not in any server yet -- invite the bot and it will "
					+ "register its commands as soon as it is added");
		}
		for (Guild guild : guilds) {
			register(guild);
		}
	}

	/** A server that invited the bot while it was already running. */
	@Override
	public void onGuildJoin(@NotNull GuildJoinEvent event) {
		LOG.info("joined {}", event.getGuild().getName());
		register(event.getGuild());
	}

	/** Publish the command set to one guild. */
	private void register(Guild guild) {
		OptionData player = new OptionData(OptionType.STRING, "player",
				"The Minecraft username", true);

		// A choice list rather than free text: an invalid grade becomes
		// unrepresentable, and graders get a picker instead of having to
		// remember the ladder.
		OptionData grade = new OptionData(OptionType.STRING, "grade",
				"The grade to assign", true);
		for (Grade value : Grade.values()) {
			grade.addChoice(value.label(), value.label());
		}

		OptionData filter = new OptionData(OptionType.STRING, "grade",
				"Only show this grade", false);
		for (Grade value : Grade.values()) {
			filter.addChoice(value.label(), value.label());
		}

		List<SlashCommandData> commands = List.of(
				Commands.slash("setgrade", "Set a player's " + LIST_NAME + " grade")
						.addOptions(player, grade),
				Commands.slash("removegrade", "Remove a player's " + LIST_NAME + " grade")
						.addOptions(player),
				Commands.slash("grade", "Look up a player's " + LIST_NAME + " grade")
						.addOptions(player),
				Commands.slash("gradelist", "List every graded player")
						.addOptions(filter));

		guild.updateCommands().addCommands(commands).queue(
				ok -> LOG.info("registered {} command(s) in {}",
						commands.size(), guild.getName()),
				error -> LOG.error("could not register commands in {}",
						guild.getName(), error));
	}

	@Override
	public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
		switch (event.getName()) {
			case "setgrade" -> setGrade(event);
			case "removegrade" -> removeGrade(event);
			case "grade" -> lookup(event);
			case "gradelist" -> list(event);
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	private void setGrade(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		Grade grade = Grade.parse(event.getOption("grade", "", OptionMapping::getAsString));
		if (grade == null) {
			event.reply("That is not a valid grade.").setEphemeral(true).queue();
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
		String message = previous == null
				? "**" + shown + "** is now **" + grade.label() + "**."
				: "**" + shown + "** moved from **" + previous.grade().label()
						+ "** to **" + grade.label() + "**.";
		LOG.info("{} set {} ({}) to {}", event.getUser().getName(), shown, id, grade.label());
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription(message)
				.setColor(new Color(grade.color()))
				.build()).queue();
	}

	private void removeGrade(SlashCommandInteractionEvent event) {
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
			event.getHook().sendMessage("**" + name + "** had no grade to remove.").queue();
			return;
		}
		LOG.info("{} removed {}'s grade ({})", event.getUser().getName(), name,
				previous.grade().label());
		event.getHook().sendMessage("Removed **" + name + "**'s grade of **"
				+ previous.grade().label() + "**.").queue();
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
					.setDescription("Not graded on " + LIST_NAME + " yet.")
					.setColor(NEUTRAL)
					.build()).queue();
			return;
		}

		EmbedBuilder embed = new EmbedBuilder()
				.setTitle(shown)
				.setThumbnail(head(id))
				.setColor(new Color(record.grade().color()))
				.addField(LIST_NAME + " Tierlist", "**" + record.grade().label() + "**", false);
		if (record.gradedBy() != null && !record.gradedBy().isBlank()) {
			// <t:unix:R> renders as "3 days ago" in each reader's own locale.
			embed.setFooter("Graded by " + record.gradedBy());
			embed.addField("Graded", "<t:" + record.gradedAt() + ":R>", false);
		}
		event.getHook().sendMessageEmbeds(embed.build()).queue();
	}

	private void list(SlashCommandInteractionEvent event) {
		Grade filter = Grade.parse(event.getOption("grade", "", OptionMapping::getAsString));
		List<GradeStore.Record> all = grades.all();

		List<String> lines = new ArrayList<>();
		for (GradeStore.Record record : all) {
			if (filter != null && record.grade() != filter) {
				continue;
			}
			lines.add("`" + pad(record.grade().label()) + "`  " + record.name());
		}

		if (lines.isEmpty()) {
			event.reply(filter == null
					? "Nobody is graded yet."
					: "Nobody is graded **" + filter.label() + "**.").queue();
			return;
		}

		// An embed description caps at 4096 characters; trim rather than have
		// Discord reject the whole message.
		StringBuilder body = new StringBuilder();
		int shown = 0;
		for (String line : lines) {
			if (body.length() + line.length() + 1 > 3900) {
				break;
			}
			body.append(line).append('\n');
			shown++;
		}
		if (shown < lines.size()) {
			body.append("\n_...and ").append(lines.size() - shown).append(" more._");
		}

		MessageEmbed embed = new EmbedBuilder()
				.setTitle(LIST_NAME + " Tierlist"
						+ (filter == null ? "" : " -- " + filter.label()))
				.setDescription(body.toString())
				.setColor(filter == null ? NEUTRAL : new Color(filter.color()))
				.setFooter(lines.size() + " player" + (lines.size() == 1 ? "" : "s"))
				.build();
		event.replyEmbeds(embed).queue();
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
		event.reply("You do not have permission to grade.").setEphemeral(true).queue();
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
				event.getHook().sendMessage("No Minecraft account called **" + name + "**.")
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

	/** Left-pads a grade label so the listing lines up in Discord's monospace. */
	private static String pad(String label) {
		return label.length() >= 2 ? label : label + " ";
	}

	private static String head(UUID id) {
		return "https://crafatar.com/avatars/" + id + "?overlay";
	}
}
