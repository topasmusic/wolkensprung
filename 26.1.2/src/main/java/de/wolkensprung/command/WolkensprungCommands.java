package de.wolkensprung.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.wolkensprung.entity.QuestGiverEntity;
import de.wolkensprung.quest.WolkensprungQuest;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class WolkensprungCommands {
    private static final double NPC_SEARCH_RADIUS = 3.0;

    private WolkensprungCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("wolkensprung")
                        .then(buildQuestCommand())
                        .then(buildNpcCommand())
                        .then(buildCourseCommand())
                        .then(buildRespawnCommand(ctx -> WolkensprungQuest.DEFAULT_COURSE_ID))
                        .then(buildAreaCommand(ctx -> WolkensprungQuest.DEFAULT_COURSE_ID))
                        .then(buildCheckpointCommand(ctx -> WolkensprungQuest.DEFAULT_COURSE_ID)))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildQuestCommand() {
        return literal("quest")
                .then(literal("accept").executes(ctx -> acceptQuest(ctx.getSource())))
                .then(literal("decline").executes(ctx -> declineQuest(ctx.getSource())))
                .then(literal("cancel").executes(ctx -> cancelActive(ctx.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNpcCommand() {
        return literal("npc")
                .requires(WolkensprungCommands::canManage)
                .then(literal("config")
                        .executes(ctx -> showNpcConfig(ctx.getSource()))
                        .then(argument("radius", IntegerArgumentType.integer(0, 64))
                                .executes(ctx -> setNpcRadius(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(literal("course")
                        .then(argument("id", StringArgumentType.word())
                                .executes(ctx -> setNpcCourse(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(literal("delete")
                        .executes(ctx -> deleteNearestNpc(ctx.getSource())))
                .then(literal("deleteid")
                        .then(argument("uuid", StringArgumentType.word())
                                .executes(ctx -> deleteNpcById(ctx.getSource(), StringArgumentType.getString(ctx, "uuid")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCourseCommand() {
        return literal("course")
                .requires(WolkensprungCommands::canManage)
                .then(literal("list").executes(ctx -> listCourses(ctx.getSource())))
                .then(literal("create")
                        .then(argument("id", StringArgumentType.word())
                                .executes(ctx -> createCourse(ctx.getSource(), courseId(ctx)))))
                .then(argument("id", StringArgumentType.word())
                        .executes(ctx -> showCourseInfo(ctx.getSource(), courseId(ctx)))
                        .then(literal("info").executes(ctx -> showCourseInfo(ctx.getSource(), courseId(ctx))))
                        .then(buildRespawnCommand(WolkensprungCommands::courseId))
                        .then(buildAreaCommand(WolkensprungCommands::courseId))
                        .then(buildCheckpointCommand(WolkensprungCommands::courseId))
                        .then(buildRewardCommand(WolkensprungCommands::courseId)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRespawnCommand(CourseResolver resolver) {
        return literal("respawn")
                .requires(WolkensprungCommands::canManage)
                .then(literal("set")
                        .executes(ctx -> setRespawnHere(ctx.getSource(), resolver.resolve(ctx)))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> setRespawn(
                                                        ctx.getSource(),
                                                        resolver.resolve(ctx),
                                                        new BlockPos(
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(literal("clear").executes(ctx -> clearRespawn(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("height")
                        .then(argument("y", IntegerArgumentType.integer())
                                .executes(ctx -> setRespawnHeight(
                                        ctx.getSource(),
                                        resolver.resolve(ctx),
                                        IntegerArgumentType.getInteger(ctx, "y")))))
                .then(literal("info").executes(ctx -> showRespawnInfo(ctx.getSource(), resolver.resolve(ctx))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAreaCommand(CourseResolver resolver) {
        return literal("area")
                .requires(WolkensprungCommands::canManage)
                .then(literal("wand").executes(ctx -> toggleAreaWand(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("add").executes(ctx -> addAreaHere(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("removeall").executes(ctx -> clearAreas(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("list").executes(ctx -> listAreas(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("set")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> addArea(
                                                        ctx.getSource(),
                                                        resolver.resolve(ctx),
                                                        new BlockPos(
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(literal("sethere").executes(ctx -> setAreaHere(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("remove")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> removeArea(
                                                        ctx.getSource(),
                                                        resolver.resolve(ctx),
                                                        new BlockPos(
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCheckpointCommand(CourseResolver resolver) {
        return literal("checkpoint")
                .requires(WolkensprungCommands::canManage)
                .then(literal("wand").executes(ctx -> toggleCheckpointWand(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("list").executes(ctx -> listCheckpoints(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("set")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> addCheckpoint(
                                                        ctx.getSource(),
                                                        resolver.resolve(ctx),
                                                        new BlockPos(
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(literal("removeall").executes(ctx -> clearCheckpoints(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("remove")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> removeCheckpoint(
                                                        ctx.getSource(),
                                                        resolver.resolve(ctx),
                                                        new BlockPos(
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRewardCommand(CourseResolver resolver) {
        return literal("reward")
                .requires(WolkensprungCommands::canManage)
                .then(literal("list").executes(ctx -> listRewards(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("clear").executes(ctx -> clearRewards(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("add")
                        .then(argument("seconds", IntegerArgumentType.integer(1, 36000))
                                .then(argument("item", StringArgumentType.word())
                                        .then(argument("count", IntegerArgumentType.integer(1, 4096))
                                                .executes(ctx -> addRewardTier(
                                                        ctx.getSource(),
                                                        resolver.resolve(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "seconds"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(literal("addcommand")
                        .then(argument("seconds", IntegerArgumentType.integer(1, 36000))
                                .then(argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> addCommandRewardTier(
                                                ctx.getSource(),
                                                resolver.resolve(ctx),
                                                IntegerArgumentType.getInteger(ctx, "seconds"),
                                                StringArgumentType.getString(ctx, "command"))))))
                .then(literal("fallback")
                        .then(argument("item", StringArgumentType.word())
                                .then(argument("count", IntegerArgumentType.integer(1, 4096))
                                        .executes(ctx -> setFallbackReward(
                                                ctx.getSource(),
                                                resolver.resolve(ctx),
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("fallbackcommand")
                        .then(argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> addFallbackCommand(
                                        ctx.getSource(),
                                        resolver.resolve(ctx),
                                        StringArgumentType.getString(ctx, "command")))))
                .then(literal("clearfallback").executes(ctx -> clearFallbackReward(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("clearcommands").executes(ctx -> clearCommandRewards(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("clearfallbackcommands").executes(ctx -> clearFallbackCommands(ctx.getSource(), resolver.resolve(ctx))))
                .then(literal("onlynewbest")
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(ctx -> setRewardOnlyNewBest(
                                        ctx.getSource(),
                                        resolver.resolve(ctx),
                                        BoolArgumentType.getBool(ctx, "value")))))
                .then(literal("oncepertier")
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(ctx -> setRewardOncePerTier(
                                        ctx.getSource(),
                                        resolver.resolve(ctx),
                                        BoolArgumentType.getBool(ctx, "value")))))
                .then(literal("dailylimit")
                        .then(argument("count", IntegerArgumentType.integer(0, 100000))
                                .executes(ctx -> setRewardDailyLimit(
                                        ctx.getSource(),
                                        resolver.resolve(ctx),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
    }

    public static boolean canManage(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return source.getServer().getPlayerList().isOp(player.nameAndId());
    }

    private static int acceptQuest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return WolkensprungQuest.acceptPendingOffer(source.getLevel(), player) ? 1 : 0;
    }

    private static int declineQuest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return WolkensprungQuest.declinePendingOffer(player) ? 1 : 0;
    }

    private static int cancelActive(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean cancelled = WolkensprungQuest.cancel(source.getLevel(), player);
        if (!cancelled) {
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.quest.no_active").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.quest.cancelled").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int showNpcConfig(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getLevel(), player);
        if (npc == null) {
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.not_found").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        BlockPos home = npc.getHomePosSafe();
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.course", npc.getCourseId()).withStyle(ChatFormatting.GREEN), false);
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.mode", modeText(npc.getWanderRadius())).withStyle(ChatFormatting.GREEN), false);
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.radius", npc.getWanderRadius()).withStyle(ChatFormatting.GREEN), false);
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.home", formatPos(home)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int setNpcRadius(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getLevel(), player);
        if (npc == null) {
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.not_found").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        npc.setHomePos(npc.blockPosition());
        npc.setWanderRadius(radius);
        sendPlayerMessage(player, Component.translatable(radius <= 0
                ? "command.wolkensprung.npc.set_stationary"
                : "command.wolkensprung.npc.set_radius", radius).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int setNpcCourse(CommandSourceStack source, String rawCourseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getLevel(), player);
        if (npc == null) {
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.not_found").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        String courseId = WolkensprungQuest.normalizeCourseId(rawCourseId);
        WolkensprungQuest.createCourse(source.getLevel(), courseId);
        npc.setCourseId(courseId);
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.course_set", courseId).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int deleteNearestNpc(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getLevel(), player);
        if (npc == null) {
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.not_found").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        npc.discard();
        sendPlayerMessage(player, Component.translatable("command.wolkensprung.npc.deleted").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int deleteNpcById(CommandSourceStack source, String rawUuid) {
        UUID id;
        try {
            id = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException ex) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.npc.invalid_id").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        for (ServerLevel world : source.getServer().getAllLevels()) {
            if (world.getEntity(id) instanceof QuestGiverEntity npc) {
                npc.discard();
                source.sendSuccess(() -> Component.translatable("command.wolkensprung.npc.deleted").withStyle(ChatFormatting.GRAY), false);
                return 1;
            }
        }

        source.sendSuccess(() -> Component.translatable("command.wolkensprung.npc.id_not_found").withStyle(ChatFormatting.RED), false);
        return 0;
    }

    private static int listCourses(CommandSourceStack source) {
        List<String> courses = WolkensprungQuest.getCourseIds();
        if (courses.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.list.empty").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.list.header", courses.size()).withStyle(ChatFormatting.GRAY), false);
        for (String courseId : courses) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.list.entry", courseId).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int createCourse(CommandSourceStack source, String rawCourseId) {
        String courseId = WolkensprungQuest.normalizeCourseId(rawCourseId);
        boolean created = WolkensprungQuest.createCourse(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable(created
                ? "command.wolkensprung.course.created"
                : "command.wolkensprung.course.exists", courseId).withStyle(created ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        return created ? 1 : 0;
    }

    private static int showCourseInfo(CommandSourceStack source, String rawCourseId) {
        String courseId = WolkensprungQuest.normalizeCourseId(rawCourseId);
        WolkensprungQuest.CourseInfo info = WolkensprungQuest.getCourseInfo(courseId);

        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.header", courseId).withStyle(ChatFormatting.GREEN), false);
        if (info.respawnPos() == null || info.respawnDim() == null) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.respawn_not_set").withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> Component.translatable(
                    "command.wolkensprung.course.info.respawn",
                    formatPos(info.respawnPos()),
                    Component.literal(info.respawnDim().identifier().toString()).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.GRAY), false);
        }
        if (info.fallYThreshold() == Integer.MIN_VALUE) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.fall_not_set").withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.fall", info.fallYThreshold()).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.spawns", info.spawnCount()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.checkpoints", info.checkpointCount()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.item_reward_tiers", info.rewardTiers().size()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.command_reward_tiers", info.commandRewardTiers().size()).withStyle(ChatFormatting.GRAY), false);
        if (info.fallbackReward() == null) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.fallback_none").withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.fallback", formatReward(info.fallbackReward())).withStyle(ChatFormatting.GRAY), false);
        }
        if (info.fallbackCommands().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.fallback_commands_none").withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.fallback_commands", info.fallbackCommands().size()).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.reward_only_new_best", stateText(info.rewardPolicy().requireNewBest())).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.course.info.reward_once_per_tier", stateText(info.rewardPolicy().oncePerTier())).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable(
                info.rewardPolicy().dailyLimit() > 0
                        ? "command.wolkensprung.course.info.reward_daily_limit"
                        : "command.wolkensprung.course.info.reward_daily_limit_none",
                info.rewardPolicy().dailyLimit()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int setRespawnHere(CommandSourceStack source, String courseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return setRespawn(source, courseId, player.blockPosition());
    }

    private static int setRespawn(CommandSourceStack source, String courseId, BlockPos pos) {
        WolkensprungQuest.setRespawn(source.getLevel(), courseId, pos);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.set", formatPos(pos)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int clearRespawn(CommandSourceStack source, String courseId) {
        WolkensprungQuest.clearRespawn(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.cleared").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int setRespawnHeight(CommandSourceStack source, String courseId, int y) {
        WolkensprungQuest.setFallYThreshold(source.getLevel(), courseId, y);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.height_set", y).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int showRespawnInfo(CommandSourceStack source, String courseId) {
        BlockPos pos = WolkensprungQuest.getRespawnPos(courseId);
        int y = WolkensprungQuest.getFallYThreshold(courseId);
        if (pos == null || WolkensprungQuest.getRespawnDim(courseId) == null) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.not_set").withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.info", formatPos(pos)).withStyle(ChatFormatting.GRAY), false);
        }
        if (y == Integer.MIN_VALUE) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.height_not_set").withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.respawn.height_info", y).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int toggleAreaWand(CommandSourceStack source, String courseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean enabled = WolkensprungQuest.toggleWand(player.getUUID(), courseId);
        sendPlayerMessage(player, Component.translatable(enabled
                ? "command.wolkensprung.area.wand.enabled"
                : "command.wolkensprung.area.wand.disabled").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int addAreaHere(CommandSourceStack source, String courseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return addArea(source, courseId, player.blockPosition().below());
    }

    private static int addArea(CommandSourceStack source, String courseId, BlockPos pos) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean added = WolkensprungQuest.addCustomSpawnChecked(source.getLevel(), player, courseId, pos);
        if (added) {
            int count = WolkensprungQuest.getCustomSpawnCount(source.getLevel(), courseId);
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.area.added", count).withStyle(ChatFormatting.GRAY), false);
        }
        return added ? 1 : 0;
    }

    private static int setAreaHere(CommandSourceStack source, String courseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean added = WolkensprungQuest.addCustomSpawnChecked(source.getLevel(), player, courseId, player.blockPosition().below());
        if (added) {
            int count = WolkensprungQuest.getCustomSpawnCount(source.getLevel(), courseId);
            sendPlayerMessage(player, Component.translatable("command.wolkensprung.area.set_here", count).withStyle(ChatFormatting.GRAY), false);
        }
        return added ? 1 : 0;
    }

    private static int clearAreas(CommandSourceStack source, String courseId) {
        int removed = WolkensprungQuest.clearCustomSpawns(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.area.cleared", removed).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int listAreas(CommandSourceStack source, String courseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        WolkensprungQuest.openSpawnListScreen(source.getLevel(), player, courseId);
        return 1;
    }

    private static int removeArea(CommandSourceStack source, String courseId, BlockPos pos) {
        boolean removed = WolkensprungQuest.removeCustomSpawn(source.getLevel(), courseId, pos);
        if (removed) {
            int count = WolkensprungQuest.getCustomSpawnCount(source.getLevel(), courseId);
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.area.removed", count).withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.area.not_found").withStyle(ChatFormatting.RED), false);
        return 0;
    }

    private static int toggleCheckpointWand(CommandSourceStack source, String courseId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean enabled = WolkensprungQuest.toggleCheckpointWand(player.getUUID(), courseId);
        sendPlayerMessage(player, Component.translatable(enabled
                ? "command.wolkensprung.checkpoint.wand.enabled"
                : "command.wolkensprung.checkpoint.wand.disabled").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int listCheckpoints(CommandSourceStack source, String courseId) {
        List<BlockPos> checkpoints = WolkensprungQuest.getCheckpoints(source.getLevel(), courseId);
        if (checkpoints.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.checkpoint.none").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.checkpoint.list_header", checkpoints.size()).withStyle(ChatFormatting.GRAY), false);
        for (BlockPos pos : checkpoints) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.checkpoint.list_entry", formatPos(pos)).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int addCheckpoint(CommandSourceStack source, String courseId, BlockPos pos) {
        boolean added = WolkensprungQuest.addCheckpointManual(source.getLevel(), courseId, pos);
        source.sendSuccess(() -> Component.translatable(added
                ? "command.wolkensprung.checkpoint.set"
                : "command.wolkensprung.checkpoint.exists").withStyle(ChatFormatting.GRAY), false);
        return added ? 1 : 0;
    }

    private static int clearCheckpoints(CommandSourceStack source, String courseId) {
        int removed = WolkensprungQuest.clearCheckpoints(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.checkpoint.cleared", removed).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int removeCheckpoint(CommandSourceStack source, String courseId, BlockPos pos) {
        boolean removed = WolkensprungQuest.removeCheckpoint(source.getLevel(), courseId, pos);
        source.sendSuccess(() -> Component.translatable(removed
                ? "command.wolkensprung.checkpoint.removed"
                : "command.wolkensprung.checkpoint.not_found").withStyle(ChatFormatting.GRAY), false);
        return removed ? 1 : 0;
    }

    private static int listRewards(CommandSourceStack source, String courseId) {
        WolkensprungQuest.CourseInfo info = WolkensprungQuest.getCourseInfo(courseId);
        boolean hasAnyRewards = !info.rewardTiers().isEmpty()
                || info.fallbackReward() != null
                || !info.commandRewardTiers().isEmpty()
                || !info.fallbackCommands().isEmpty();
        boolean hasPolicies = info.rewardPolicy().requireNewBest()
                || info.rewardPolicy().oncePerTier()
                || info.rewardPolicy().dailyLimit() > 0;
        if (!hasAnyRewards && !hasPolicies) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.none").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.list_header", WolkensprungQuest.normalizeCourseId(courseId)).withStyle(ChatFormatting.GRAY), false);
        if (info.rewardTiers().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.item_none").withStyle(ChatFormatting.GRAY), false);
        } else {
            for (WolkensprungQuest.RewardTierInfo tier : info.rewardTiers()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.wolkensprung.reward.list_entry",
                        tier.maxSeconds(),
                        formatReward(new WolkensprungQuest.RewardInfo(tier.itemId(), tier.count()))).withStyle(ChatFormatting.GRAY), false);
            }
        }
        if (info.fallbackReward() != null) {
            source.sendSuccess(() -> Component.translatable(
                    "command.wolkensprung.reward.fallback_entry",
                    formatReward(info.fallbackReward())).withStyle(ChatFormatting.GRAY), false);
        }
        if (info.commandRewardTiers().isEmpty() && info.fallbackCommands().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.command_none").withStyle(ChatFormatting.GRAY), false);
        } else {
            for (WolkensprungQuest.CommandRewardTierInfo tier : info.commandRewardTiers()) {
                for (String command : tier.commands()) {
                    source.sendSuccess(() -> Component.translatable(
                            "command.wolkensprung.reward.command_entry",
                            tier.maxSeconds(),
                            formatCommand(command)).withStyle(ChatFormatting.GRAY), false);
                }
            }
            for (String command : info.fallbackCommands()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.wolkensprung.reward.fallback_command_entry",
                        formatCommand(command)).withStyle(ChatFormatting.GRAY), false);
            }
        }
        source.sendSuccess(() -> Component.translatable(
                "command.wolkensprung.reward.policy.only_new_best",
                stateText(info.rewardPolicy().requireNewBest())).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable(
                "command.wolkensprung.reward.policy.once_per_tier",
                stateText(info.rewardPolicy().oncePerTier())).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable(
                info.rewardPolicy().dailyLimit() > 0
                        ? "command.wolkensprung.reward.policy.daily_limit"
                        : "command.wolkensprung.reward.policy.daily_limit_none",
                info.rewardPolicy().dailyLimit()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int addRewardTier(CommandSourceStack source, String courseId, int seconds, String itemId, int count) {
        WolkensprungQuest.RewardTierInfo reward = WolkensprungQuest.setRewardTier(source.getLevel(), courseId, seconds, itemId, count);
        if (reward == null) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.invalid_item").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.wolkensprung.reward.added",
                seconds,
                formatReward(new WolkensprungQuest.RewardInfo(reward.itemId(), reward.count()))).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int clearRewards(CommandSourceStack source, String courseId) {
        int removed = WolkensprungQuest.clearRewardTiers(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.cleared", removed).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int addCommandRewardTier(CommandSourceStack source, String courseId, int seconds, String command) {
        WolkensprungQuest.CommandRewardTierInfo tier = WolkensprungQuest.addCommandRewardTier(source.getLevel(), courseId, seconds, command);
        if (tier == null) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.invalid_command").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.wolkensprung.reward.command_added",
                seconds,
                formatCommand(command)).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int setFallbackReward(CommandSourceStack source, String courseId, String itemId, int count) {
        WolkensprungQuest.RewardInfo reward = WolkensprungQuest.setFallbackReward(source.getLevel(), courseId, itemId, count);
        if (reward == null) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.invalid_item").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.fallback_set", formatReward(reward)).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int addFallbackCommand(CommandSourceStack source, String courseId, String command) {
        boolean added = WolkensprungQuest.addFallbackCommand(source.getLevel(), courseId, command);
        if (!added) {
            source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.invalid_command").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.fallback_command_set", formatCommand(command)).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int clearFallbackReward(CommandSourceStack source, String courseId) {
        boolean removed = WolkensprungQuest.clearFallbackReward(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable(removed
                ? "command.wolkensprung.reward.fallback_cleared"
                : "command.wolkensprung.reward.fallback_not_set").withStyle(removed ? ChatFormatting.GRAY : ChatFormatting.RED), false);
        return removed ? 1 : 0;
    }

    private static int clearCommandRewards(CommandSourceStack source, String courseId) {
        int removed = WolkensprungQuest.clearCommandRewardTiers(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.command_cleared", removed).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int clearFallbackCommands(CommandSourceStack source, String courseId) {
        int removed = WolkensprungQuest.clearFallbackCommands(source.getLevel(), courseId);
        source.sendSuccess(() -> Component.translatable(
                removed > 0
                        ? "command.wolkensprung.reward.fallback_command_cleared"
                        : "command.wolkensprung.reward.fallback_command_not_set",
                removed).withStyle(removed > 0 ? ChatFormatting.GRAY : ChatFormatting.RED), false);
        return removed > 0 ? 1 : 0;
    }

    private static int setRewardOnlyNewBest(CommandSourceStack source, String courseId, boolean value) {
        WolkensprungQuest.setRewardRequireNewBest(source.getLevel(), courseId, value);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.only_new_best_set", stateText(value)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int setRewardOncePerTier(CommandSourceStack source, String courseId, boolean value) {
        WolkensprungQuest.setRewardOncePerTier(source.getLevel(), courseId, value);
        source.sendSuccess(() -> Component.translatable("command.wolkensprung.reward.once_per_tier_set", stateText(value)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int setRewardDailyLimit(CommandSourceStack source, String courseId, int value) {
        WolkensprungQuest.setRewardDailyLimit(source.getLevel(), courseId, value);
        source.sendSuccess(() -> Component.translatable(
                value > 0
                        ? "command.wolkensprung.reward.daily_limit_set"
                        : "command.wolkensprung.reward.daily_limit_cleared",
                value).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static QuestGiverEntity findNearestNpc(ServerLevel world, ServerPlayer player) {
        AABB range = player.getBoundingBox().inflate(NPC_SEARCH_RADIUS);
        return world.getEntitiesOfClass(QuestGiverEntity.class, range, entity -> true)
                .stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
                .orElse(null);
    }

    private static void sendPlayerMessage(ServerPlayer player, Component component, boolean ignored) {
        player.sendSystemMessage(component);
    }

    private static String courseId(CommandContext<CommandSourceStack> ctx) {
        return WolkensprungQuest.normalizeCourseId(StringArgumentType.getString(ctx, "id"));
    }

    private static Component formatPos(BlockPos pos) {
        return Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.YELLOW);
    }

    private static Component modeText(int radius) {
        return Component.translatable(radius <= 0
                ? "command.wolkensprung.npc.mode.stationary"
                : "command.wolkensprung.npc.mode.patrol");
    }

    private static Component formatReward(WolkensprungQuest.RewardInfo reward) {
        return Component.literal(reward.count() + "x " + reward.itemId()).withStyle(ChatFormatting.YELLOW);
    }

    private static Component formatCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return Component.literal(normalized).withStyle(ChatFormatting.YELLOW);
    }

    private static Component stateText(boolean value) {
        return Component.translatable(value
                ? "command.wolkensprung.common.on"
                : "command.wolkensprung.common.off").withStyle(ChatFormatting.YELLOW);
    }

    @FunctionalInterface
    private interface CourseResolver {
        String resolve(CommandContext<CommandSourceStack> ctx);
    }
}
