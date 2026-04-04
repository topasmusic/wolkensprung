package de.wolkensprung.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.wolkensprung.entity.QuestGiverEntity;
import de.wolkensprung.quest.WolkensprungQuest;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

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

    private static LiteralArgumentBuilder<ServerCommandSource> buildQuestCommand() {
        return literal("quest")
                .then(literal("accept").executes(ctx -> acceptQuest(ctx.getSource())))
                .then(literal("decline").executes(ctx -> declineQuest(ctx.getSource())))
                .then(literal("cancel").executes(ctx -> cancelActive(ctx.getSource())));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildNpcCommand() {
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

    private static LiteralArgumentBuilder<ServerCommandSource> buildCourseCommand() {
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

    private static LiteralArgumentBuilder<ServerCommandSource> buildRespawnCommand(CourseResolver resolver) {
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

    private static LiteralArgumentBuilder<ServerCommandSource> buildAreaCommand(CourseResolver resolver) {
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

    private static LiteralArgumentBuilder<ServerCommandSource> buildCheckpointCommand(CourseResolver resolver) {
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

    private static LiteralArgumentBuilder<ServerCommandSource> buildRewardCommand(CourseResolver resolver) {
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

    public static boolean canManage(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return source.getServer().getPlayerManager().isOperator(player.getPlayerConfigEntry());
    }

    private static int acceptQuest(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return WolkensprungQuest.acceptPendingOffer(source.getWorld(), player) ? 1 : 0;
    }

    private static int declineQuest(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return WolkensprungQuest.declinePendingOffer(player) ? 1 : 0;
    }

    private static int cancelActive(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean cancelled = WolkensprungQuest.cancel(source.getWorld(), player);
        if (!cancelled) {
            player.sendMessage(Text.translatable("command.wolkensprung.quest.no_active").formatted(Formatting.RED), false);
            return 0;
        }
        player.sendMessage(Text.translatable("command.wolkensprung.quest.cancelled").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int showNpcConfig(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getWorld(), player);
        if (npc == null) {
            player.sendMessage(Text.translatable("command.wolkensprung.npc.not_found").formatted(Formatting.RED), false);
            return 0;
        }
        BlockPos home = npc.getHomePosSafe();
        player.sendMessage(Text.translatable("command.wolkensprung.npc.course", npc.getCourseId()).formatted(Formatting.GREEN), false);
        player.sendMessage(Text.translatable("command.wolkensprung.npc.mode", modeText(npc.getWanderRadius())).formatted(Formatting.GREEN), false);
        player.sendMessage(Text.translatable("command.wolkensprung.npc.radius", npc.getWanderRadius()).formatted(Formatting.GREEN), false);
        player.sendMessage(Text.translatable("command.wolkensprung.npc.home", formatPos(home)).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int setNpcRadius(ServerCommandSource source, int radius) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getWorld(), player);
        if (npc == null) {
            player.sendMessage(Text.translatable("command.wolkensprung.npc.not_found").formatted(Formatting.RED), false);
            return 0;
        }
        npc.setHomePos(npc.getBlockPos());
        npc.setWanderRadius(radius);
        player.sendMessage(Text.translatable(radius <= 0
                ? "command.wolkensprung.npc.set_stationary"
                : "command.wolkensprung.npc.set_radius", radius).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int setNpcCourse(ServerCommandSource source, String rawCourseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getWorld(), player);
        if (npc == null) {
            player.sendMessage(Text.translatable("command.wolkensprung.npc.not_found").formatted(Formatting.RED), false);
            return 0;
        }

        String courseId = WolkensprungQuest.normalizeCourseId(rawCourseId);
        WolkensprungQuest.createCourse(source.getWorld(), courseId);
        npc.setCourseId(courseId);
        player.sendMessage(Text.translatable("command.wolkensprung.npc.course_set", courseId).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int deleteNearestNpc(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestGiverEntity npc = findNearestNpc(source.getWorld(), player);
        if (npc == null) {
            player.sendMessage(Text.translatable("command.wolkensprung.npc.not_found").formatted(Formatting.RED), false);
            return 0;
        }
        npc.discard();
        player.sendMessage(Text.translatable("command.wolkensprung.npc.deleted").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int deleteNpcById(ServerCommandSource source, String rawUuid) {
        UUID id;
        try {
            id = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException ex) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.npc.invalid_id").formatted(Formatting.RED), false);
            return 0;
        }

        for (ServerWorld world : source.getServer().getWorlds()) {
            if (world.getEntity(id) instanceof QuestGiverEntity npc) {
                npc.discard();
                source.sendFeedback(() -> Text.translatable("command.wolkensprung.npc.deleted").formatted(Formatting.GRAY), false);
                return 1;
            }
        }

        source.sendFeedback(() -> Text.translatable("command.wolkensprung.npc.id_not_found").formatted(Formatting.RED), false);
        return 0;
    }

    private static int listCourses(ServerCommandSource source) {
        List<String> courses = WolkensprungQuest.getCourseIds();
        if (courses.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.list.empty").formatted(Formatting.GRAY), false);
            return 1;
        }

        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.list.header", courses.size()).formatted(Formatting.GRAY), false);
        for (String courseId : courses) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.list.entry", courseId).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int createCourse(ServerCommandSource source, String rawCourseId) {
        String courseId = WolkensprungQuest.normalizeCourseId(rawCourseId);
        boolean created = WolkensprungQuest.createCourse(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable(created
                ? "command.wolkensprung.course.created"
                : "command.wolkensprung.course.exists", courseId).formatted(created ? Formatting.GREEN : Formatting.GRAY), false);
        return created ? 1 : 0;
    }

    private static int showCourseInfo(ServerCommandSource source, String rawCourseId) {
        String courseId = WolkensprungQuest.normalizeCourseId(rawCourseId);
        WolkensprungQuest.CourseInfo info = WolkensprungQuest.getCourseInfo(courseId);

        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.header", courseId).formatted(Formatting.GREEN), false);
        if (info.respawnPos() == null || info.respawnDim() == null) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.respawn_not_set").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.translatable(
                    "command.wolkensprung.course.info.respawn",
                    formatPos(info.respawnPos()),
                    Text.literal(info.respawnDim().getValue().toString()).formatted(Formatting.YELLOW)).formatted(Formatting.GRAY), false);
        }
        if (info.fallYThreshold() == Integer.MIN_VALUE) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.fall_not_set").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.fall", info.fallYThreshold()).formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.spawns", info.spawnCount()).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.checkpoints", info.checkpointCount()).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.item_reward_tiers", info.rewardTiers().size()).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.command_reward_tiers", info.commandRewardTiers().size()).formatted(Formatting.GRAY), false);
        if (info.fallbackReward() == null) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.fallback_none").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.fallback", formatReward(info.fallbackReward())).formatted(Formatting.GRAY), false);
        }
        if (info.fallbackCommands().isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.fallback_commands_none").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.fallback_commands", info.fallbackCommands().size()).formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.reward_only_new_best", stateText(info.rewardPolicy().requireNewBest())).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.course.info.reward_once_per_tier", stateText(info.rewardPolicy().oncePerTier())).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable(
                info.rewardPolicy().dailyLimit() > 0
                        ? "command.wolkensprung.course.info.reward_daily_limit"
                        : "command.wolkensprung.course.info.reward_daily_limit_none",
                info.rewardPolicy().dailyLimit()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int setRespawnHere(ServerCommandSource source, String courseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return setRespawn(source, courseId, player.getBlockPos());
    }

    private static int setRespawn(ServerCommandSource source, String courseId, BlockPos pos) {
        WolkensprungQuest.setRespawn(source.getWorld(), courseId, pos);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.set", formatPos(pos)).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int clearRespawn(ServerCommandSource source, String courseId) {
        WolkensprungQuest.clearRespawn(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.cleared").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int setRespawnHeight(ServerCommandSource source, String courseId, int y) {
        WolkensprungQuest.setFallYThreshold(source.getWorld(), courseId, y);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.height_set", y).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int showRespawnInfo(ServerCommandSource source, String courseId) {
        BlockPos pos = WolkensprungQuest.getRespawnPos(courseId);
        int y = WolkensprungQuest.getFallYThreshold(courseId);
        if (pos == null || WolkensprungQuest.getRespawnDim(courseId) == null) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.not_set").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.info", formatPos(pos)).formatted(Formatting.GRAY), false);
        }
        if (y == Integer.MIN_VALUE) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.height_not_set").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.respawn.height_info", y).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int toggleAreaWand(ServerCommandSource source, String courseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean enabled = WolkensprungQuest.toggleWand(player.getUuid(), courseId);
        player.sendMessage(Text.translatable(enabled
                ? "command.wolkensprung.area.wand.enabled"
                : "command.wolkensprung.area.wand.disabled").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int addAreaHere(ServerCommandSource source, String courseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        return addArea(source, courseId, player.getBlockPos().down());
    }

    private static int addArea(ServerCommandSource source, String courseId, BlockPos pos) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean added = WolkensprungQuest.addCustomSpawnChecked(source.getWorld(), player, courseId, pos);
        if (added) {
            int count = WolkensprungQuest.getCustomSpawnCount(source.getWorld(), courseId);
            player.sendMessage(Text.translatable("command.wolkensprung.area.added", count).formatted(Formatting.GRAY), false);
        }
        return added ? 1 : 0;
    }

    private static int setAreaHere(ServerCommandSource source, String courseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean added = WolkensprungQuest.addCustomSpawnChecked(source.getWorld(), player, courseId, player.getBlockPos().down());
        if (added) {
            int count = WolkensprungQuest.getCustomSpawnCount(source.getWorld(), courseId);
            player.sendMessage(Text.translatable("command.wolkensprung.area.set_here", count).formatted(Formatting.GRAY), false);
        }
        return added ? 1 : 0;
    }

    private static int clearAreas(ServerCommandSource source, String courseId) {
        int removed = WolkensprungQuest.clearCustomSpawns(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.area.cleared", removed).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int listAreas(ServerCommandSource source, String courseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        WolkensprungQuest.openSpawnListScreen(source.getWorld(), player, courseId);
        return 1;
    }

    private static int removeArea(ServerCommandSource source, String courseId, BlockPos pos) {
        boolean removed = WolkensprungQuest.removeCustomSpawn(source.getWorld(), courseId, pos);
        if (removed) {
            int count = WolkensprungQuest.getCustomSpawnCount(source.getWorld(), courseId);
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.area.removed", count).formatted(Formatting.GRAY), false);
            return 1;
        }
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.area.not_found").formatted(Formatting.RED), false);
        return 0;
    }

    private static int toggleCheckpointWand(ServerCommandSource source, String courseId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean enabled = WolkensprungQuest.toggleCheckpointWand(player.getUuid(), courseId);
        player.sendMessage(Text.translatable(enabled
                ? "command.wolkensprung.checkpoint.wand.enabled"
                : "command.wolkensprung.checkpoint.wand.disabled").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int listCheckpoints(ServerCommandSource source, String courseId) {
        List<BlockPos> checkpoints = WolkensprungQuest.getCheckpoints(source.getWorld(), courseId);
        if (checkpoints.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.checkpoint.none").formatted(Formatting.GRAY), false);
            return 1;
        }
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.checkpoint.list_header", checkpoints.size()).formatted(Formatting.GRAY), false);
        for (BlockPos pos : checkpoints) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.checkpoint.list_entry", formatPos(pos)).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int addCheckpoint(ServerCommandSource source, String courseId, BlockPos pos) {
        boolean added = WolkensprungQuest.addCheckpointManual(source.getWorld(), courseId, pos);
        source.sendFeedback(() -> Text.translatable(added
                ? "command.wolkensprung.checkpoint.set"
                : "command.wolkensprung.checkpoint.exists").formatted(Formatting.GRAY), false);
        return added ? 1 : 0;
    }

    private static int clearCheckpoints(ServerCommandSource source, String courseId) {
        int removed = WolkensprungQuest.clearCheckpoints(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.checkpoint.cleared", removed).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int removeCheckpoint(ServerCommandSource source, String courseId, BlockPos pos) {
        boolean removed = WolkensprungQuest.removeCheckpoint(source.getWorld(), courseId, pos);
        source.sendFeedback(() -> Text.translatable(removed
                ? "command.wolkensprung.checkpoint.removed"
                : "command.wolkensprung.checkpoint.not_found").formatted(Formatting.GRAY), false);
        return removed ? 1 : 0;
    }

    private static int listRewards(ServerCommandSource source, String courseId) {
        WolkensprungQuest.CourseInfo info = WolkensprungQuest.getCourseInfo(courseId);
        boolean hasAnyRewards = !info.rewardTiers().isEmpty()
                || info.fallbackReward() != null
                || !info.commandRewardTiers().isEmpty()
                || !info.fallbackCommands().isEmpty();
        boolean hasPolicies = info.rewardPolicy().requireNewBest()
                || info.rewardPolicy().oncePerTier()
                || info.rewardPolicy().dailyLimit() > 0;
        if (!hasAnyRewards && !hasPolicies) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.none").formatted(Formatting.GRAY), false);
            return 1;
        }

        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.list_header", WolkensprungQuest.normalizeCourseId(courseId)).formatted(Formatting.GRAY), false);
        if (info.rewardTiers().isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.item_none").formatted(Formatting.GRAY), false);
        } else {
            for (WolkensprungQuest.RewardTierInfo tier : info.rewardTiers()) {
                source.sendFeedback(() -> Text.translatable(
                        "command.wolkensprung.reward.list_entry",
                        tier.maxSeconds(),
                        formatReward(new WolkensprungQuest.RewardInfo(tier.itemId(), tier.count()))).formatted(Formatting.GRAY), false);
            }
        }
        if (info.fallbackReward() != null) {
            source.sendFeedback(() -> Text.translatable(
                    "command.wolkensprung.reward.fallback_entry",
                    formatReward(info.fallbackReward())).formatted(Formatting.GRAY), false);
        }
        if (info.commandRewardTiers().isEmpty() && info.fallbackCommands().isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.command_none").formatted(Formatting.GRAY), false);
        } else {
            for (WolkensprungQuest.CommandRewardTierInfo tier : info.commandRewardTiers()) {
                for (String command : tier.commands()) {
                    source.sendFeedback(() -> Text.translatable(
                            "command.wolkensprung.reward.command_entry",
                            tier.maxSeconds(),
                            formatCommand(command)).formatted(Formatting.GRAY), false);
                }
            }
            for (String command : info.fallbackCommands()) {
                source.sendFeedback(() -> Text.translatable(
                        "command.wolkensprung.reward.fallback_command_entry",
                        formatCommand(command)).formatted(Formatting.GRAY), false);
            }
        }
        source.sendFeedback(() -> Text.translatable(
                "command.wolkensprung.reward.policy.only_new_best",
                stateText(info.rewardPolicy().requireNewBest())).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable(
                "command.wolkensprung.reward.policy.once_per_tier",
                stateText(info.rewardPolicy().oncePerTier())).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.translatable(
                info.rewardPolicy().dailyLimit() > 0
                        ? "command.wolkensprung.reward.policy.daily_limit"
                        : "command.wolkensprung.reward.policy.daily_limit_none",
                info.rewardPolicy().dailyLimit()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int addRewardTier(ServerCommandSource source, String courseId, int seconds, String itemId, int count) {
        WolkensprungQuest.RewardTierInfo reward = WolkensprungQuest.setRewardTier(source.getWorld(), courseId, seconds, itemId, count);
        if (reward == null) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.invalid_item").formatted(Formatting.RED), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable(
                "command.wolkensprung.reward.added",
                seconds,
                formatReward(new WolkensprungQuest.RewardInfo(reward.itemId(), reward.count()))).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int clearRewards(ServerCommandSource source, String courseId) {
        int removed = WolkensprungQuest.clearRewardTiers(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.cleared", removed).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int addCommandRewardTier(ServerCommandSource source, String courseId, int seconds, String command) {
        WolkensprungQuest.CommandRewardTierInfo tier = WolkensprungQuest.addCommandRewardTier(source.getWorld(), courseId, seconds, command);
        if (tier == null) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.invalid_command").formatted(Formatting.RED), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable(
                "command.wolkensprung.reward.command_added",
                seconds,
                formatCommand(command)).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int setFallbackReward(ServerCommandSource source, String courseId, String itemId, int count) {
        WolkensprungQuest.RewardInfo reward = WolkensprungQuest.setFallbackReward(source.getWorld(), courseId, itemId, count);
        if (reward == null) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.invalid_item").formatted(Formatting.RED), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.fallback_set", formatReward(reward)).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int addFallbackCommand(ServerCommandSource source, String courseId, String command) {
        boolean added = WolkensprungQuest.addFallbackCommand(source.getWorld(), courseId, command);
        if (!added) {
            source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.invalid_command").formatted(Formatting.RED), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.fallback_command_set", formatCommand(command)).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int clearFallbackReward(ServerCommandSource source, String courseId) {
        boolean removed = WolkensprungQuest.clearFallbackReward(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable(removed
                ? "command.wolkensprung.reward.fallback_cleared"
                : "command.wolkensprung.reward.fallback_not_set").formatted(removed ? Formatting.GRAY : Formatting.RED), false);
        return removed ? 1 : 0;
    }

    private static int clearCommandRewards(ServerCommandSource source, String courseId) {
        int removed = WolkensprungQuest.clearCommandRewardTiers(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.command_cleared", removed).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int clearFallbackCommands(ServerCommandSource source, String courseId) {
        int removed = WolkensprungQuest.clearFallbackCommands(source.getWorld(), courseId);
        source.sendFeedback(() -> Text.translatable(
                removed > 0
                        ? "command.wolkensprung.reward.fallback_command_cleared"
                        : "command.wolkensprung.reward.fallback_command_not_set",
                removed).formatted(removed > 0 ? Formatting.GRAY : Formatting.RED), false);
        return removed > 0 ? 1 : 0;
    }

    private static int setRewardOnlyNewBest(ServerCommandSource source, String courseId, boolean value) {
        WolkensprungQuest.setRewardRequireNewBest(source.getWorld(), courseId, value);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.only_new_best_set", stateText(value)).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int setRewardOncePerTier(ServerCommandSource source, String courseId, boolean value) {
        WolkensprungQuest.setRewardOncePerTier(source.getWorld(), courseId, value);
        source.sendFeedback(() -> Text.translatable("command.wolkensprung.reward.once_per_tier_set", stateText(value)).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int setRewardDailyLimit(ServerCommandSource source, String courseId, int value) {
        WolkensprungQuest.setRewardDailyLimit(source.getWorld(), courseId, value);
        source.sendFeedback(() -> Text.translatable(
                value > 0
                        ? "command.wolkensprung.reward.daily_limit_set"
                        : "command.wolkensprung.reward.daily_limit_cleared",
                value).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static QuestGiverEntity findNearestNpc(ServerWorld world, ServerPlayerEntity player) {
        Box range = player.getBoundingBox().expand(NPC_SEARCH_RADIUS);
        return world.getEntitiesByClass(QuestGiverEntity.class, range, entity -> true)
                .stream()
                .min((a, b) -> Double.compare(a.squaredDistanceTo(player), b.squaredDistanceTo(player)))
                .orElse(null);
    }

    private static String courseId(CommandContext<ServerCommandSource> ctx) {
        return WolkensprungQuest.normalizeCourseId(StringArgumentType.getString(ctx, "id"));
    }

    private static Text formatPos(BlockPos pos) {
        return Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).formatted(Formatting.YELLOW);
    }

    private static Text modeText(int radius) {
        return Text.translatable(radius <= 0
                ? "command.wolkensprung.npc.mode.stationary"
                : "command.wolkensprung.npc.mode.patrol");
    }

    private static Text formatReward(WolkensprungQuest.RewardInfo reward) {
        return Text.literal(reward.count() + "x " + reward.itemId()).formatted(Formatting.YELLOW);
    }

    private static Text formatCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return Text.literal(normalized).formatted(Formatting.YELLOW);
    }

    private static Text stateText(boolean value) {
        return Text.translatable(value
                ? "command.wolkensprung.common.on"
                : "command.wolkensprung.common.off").formatted(Formatting.YELLOW);
    }

    @FunctionalInterface
    private interface CourseResolver {
        String resolve(CommandContext<ServerCommandSource> ctx);
    }
}
