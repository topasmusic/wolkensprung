package de.wolkensprung.quest;

import de.wolkensprung.data.WolkensprungState;
import de.wolkensprung.entity.QuestGiverEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WolkensprungQuest {
    public static final String DEFAULT_COURSE_ID = "default";

    private static final String QUEST_VILLAGER_NAME = "Meister Hanno";
    private static final String QUEST_ACCEPT_COMMAND = "/wolkensprung quest accept";
    private static final String QUEST_DECLINE_COMMAND = "/wolkensprung quest decline";

    private static final Map<String, CourseConfig> COURSES = new ConcurrentHashMap<>();
    private static final Map<UUID, ActiveRun> ACTIVE_RUNS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingOffer> PENDING_OFFERS = new ConcurrentHashMap<>();
    private static final Map<UUID, ActiveCheckpoint> CHECKPOINT_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> COMPLETED_COURSES = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> DISCOVERED_COURSES = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Long>> PERSONAL_BEST_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, RewardDailyState>> DAILY_REWARD_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Set<String>>> CLAIMED_REWARD_KEYS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_DIALOG_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ELYTRA_WARN = new ConcurrentHashMap<>();
    private static final Map<UUID, String> AREA_WAND_COURSE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> CHECKPOINT_WAND_COURSE = new ConcurrentHashMap<>();

    public enum OfferType {
        MAIN,
        TIMER
    }

    public enum RewardBlockReason {
        NEW_BEST_ONLY,
        DAILY_LIMIT,
        TIER_ALREADY_CLAIMED
    }

    public record RewardTierInfo(int maxSeconds, String itemId, int count) {}

    public record CommandRewardTierInfo(int maxSeconds, List<String> commands) {}

    public record RewardInfo(String itemId, int count) {}

    public record RewardPolicyInfo(boolean requireNewBest, boolean oncePerTier, int dailyLimit) {}

    public record CourseInfo(
            String courseId,
            BlockPos respawnPos,
            ResourceKey<Level> respawnDim,
            int fallYThreshold,
            int spawnCount,
            int checkpointCount,
            List<RewardTierInfo> rewardTiers,
            RewardInfo fallbackReward,
            List<CommandRewardTierInfo> commandRewardTiers,
            List<String> fallbackCommands,
            RewardPolicyInfo rewardPolicy
    ) {}

    private WolkensprungQuest() {}

    public static String normalizeCourseId(String rawCourseId) {
        if (rawCourseId == null || rawCourseId.isBlank()) {
            return DEFAULT_COURSE_ID;
        }
        return rawCourseId.trim().toLowerCase(Locale.ROOT);
    }

    public static InteractionResult onVillagerInteract(ServerLevel world, ServerPlayer player, InteractionHand hand, Entity entity) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!isQuestGiver(entity)) {
            return InteractionResult.PASS;
        }

        String courseId = getQuestGiverCourseId(entity);
        markDiscovered(world, player.getUUID(), courseId);
        return handleQuestDialog(world, player, courseId);
    }

    public static void onServerTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ActiveRun activeRun = ACTIVE_RUNS.get(player.getUUID());
            if (activeRun == null) {
                continue;
            }

            if (player.isFallFlying()) {
                player.stopFallFlying();
            }

            ServerLevel playerWorld = (ServerLevel) player.level();
            updateCheckpointProgress(playerWorld, player, activeRun);

            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA)) {
                ItemStack removed = chest.copy();
                player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                if (!player.getInventory().add(removed)) {
                    player.drop(removed, false);
                }
                warnElytraBlocked(player, playerWorld);
            }

            CourseConfig course = getCourse(activeRun.courseId());
            if (course == null || course.respawnPos == null || course.respawnDim == null || course.fallYThreshold == Integer.MIN_VALUE) {
                continue;
            }
            if (!playerWorld.dimension().equals(course.respawnDim)) {
                continue;
            }
            if (player.getY() < course.fallYThreshold) {
                ServerLevel targetWorld = server.getLevel(course.respawnDim);
                if (targetWorld != null) {
                    teleportToRespawn(player, targetWorld, activeRun.courseId());
                    continue;
                }
            }
            if ((player.tickCount & 7) == 0 && playerWorld.dimension().equals(activeRun.dimension())) {
                emitChestBeam(playerWorld, player, activeRun.pos());
            }
        }
    }

    public static InteractionResult onBlockUse(ServerLevel world, ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);

        String checkpointCourseId = CHECKPOINT_WAND_COURSE.get(player.getUUID());
        if (checkpointCourseId != null && player.getItemInHand(hand).is(Items.STICK)) {
            addCheckpoint(world, player, checkpointCourseId, pos);
            return InteractionResult.SUCCESS;
        }

        String areaCourseId = AREA_WAND_COURSE.get(player.getUUID());
        if (areaCourseId != null && player.getItemInHand(hand).is(Items.STICK)) {
            boolean added = addCustomSpawnChecked(world, player, areaCourseId, pos);
            if (added) {
                int count = getCustomSpawnCount(world, areaCourseId);
                sendPlayerMessage(player, Component.translatable("quest.wolkensprung.spawn_wand_added", count).withStyle(ChatFormatting.GRAY), false);
            }
            return InteractionResult.SUCCESS;
        }

        if (!state.is(Blocks.CHEST)) {
            return InteractionResult.PASS;
        }

        ActiveRun tracked = ACTIVE_RUNS.get(player.getUUID());
        if (tracked == null || !tracked.dimension().equals(world.dimension()) || !tracked.pos().equals(pos)) {
            return InteractionResult.PASS;
        }

        finish(world, player, tracked);
        return InteractionResult.SUCCESS;
    }

    public static boolean acceptPendingOffer(ServerLevel world, ServerPlayer player) {
        PendingOffer offer = player == null ? null : PENDING_OFFERS.remove(player.getUUID());
        if (offer == null) {
            if (player != null) {
                sendPlayerMessage(player, Component.translatable("command.wolkensprung.quest.no_offer").withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        if (offer.type() == OfferType.TIMER) {
            return acceptTimer(world, player, offer.courseId());
        }
        return accept(world, player, offer.courseId());
    }

    public static boolean declinePendingOffer(ServerPlayer player) {
        PendingOffer offer = player == null ? null : PENDING_OFFERS.remove(player.getUUID());
        if (offer == null) {
            if (player != null) {
                sendPlayerMessage(player, Component.translatable("command.wolkensprung.quest.no_offer").withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        if (offer.type() == OfferType.TIMER) {
            declineTimer(player);
        } else {
            declineOffer(player);
        }
        return true;
    }

    public static boolean cancel(ServerLevel world, ServerPlayer player) {
        UUID playerId = player.getUUID();
        ActiveRun run = ACTIVE_RUNS.remove(playerId);
        if (run != null) {
            ServerLevel targetWorld = world.getServer().getLevel(run.dimension());
            if (targetWorld != null && targetWorld.getBlockState(run.pos()).is(Blocks.CHEST)) {
                targetWorld.destroyBlock(run.pos(), false, player);
            }
        }
        clearPlayerCheckpoints(world, playerId);
        markDirty(world);
        return run != null;
    }

    public static boolean hasActive(UUID playerId) {
        return ACTIVE_RUNS.containsKey(playerId);
    }

    public static boolean hasCompleted(UUID playerId) {
        Set<String> courses = COMPLETED_COURSES.get(playerId);
        return courses != null && !courses.isEmpty();
    }

    public static boolean hasCompleted(UUID playerId, String rawCourseId) {
        if (playerId == null) {
            return false;
        }
        Set<String> courses = COMPLETED_COURSES.get(playerId);
        return courses != null && courses.contains(normalizeCourseId(rawCourseId));
    }

    public static void markDiscovered(UUID playerId) {
        addCourseFlag(DISCOVERED_COURSES, playerId, DEFAULT_COURSE_ID);
    }

    public static void markDiscovered(ServerLevel world, UUID playerId) {
        markDiscovered(world, playerId, DEFAULT_COURSE_ID);
    }

    public static boolean hasDiscovered(UUID playerId) {
        Set<String> courses = DISCOVERED_COURSES.get(playerId);
        return courses != null && !courses.isEmpty();
    }

    public static boolean createCourse(ServerLevel world, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        if (COURSES.containsKey(courseId)) {
            return false;
        }
        COURSES.put(courseId, new CourseConfig(courseId));
        markDirty(world);
        return true;
    }

    public static List<String> getCourseIds() {
        List<String> ids = new ArrayList<>(COURSES.keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    public static CourseInfo getCourseInfo(String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        CourseConfig course = getCourse(courseId);
        if (course == null) {
            return new CourseInfo(courseId, null, null, Integer.MIN_VALUE, 0, 0, List.of(), null, List.of(), List.of(), new RewardPolicyInfo(false, false, 0));
        }

        List<RewardTierInfo> tiers = course.rewardTiers.stream()
                .sorted(Comparator.comparingInt(RewardTier::maxSeconds))
                .map(tier -> new RewardTierInfo(
                        tier.maxSeconds(),
                        itemId(tier.reward().item()),
                        tier.reward().count()))
                .toList();

        RewardInfo fallback = course.fallbackReward == null
                ? null
                : new RewardInfo(itemId(course.fallbackReward.item()), course.fallbackReward.count());
        List<CommandRewardTierInfo> commandTiers = course.commandRewardTiers.stream()
                .sorted(Comparator.comparingInt(CommandRewardTier::maxSeconds))
                .map(tier -> new CommandRewardTierInfo(tier.maxSeconds(), List.copyOf(tier.commands())))
                .toList();

        return new CourseInfo(
                courseId,
                course.respawnPos,
                course.respawnDim,
                course.fallYThreshold,
                countPositions(course.customSpawns),
                countPositions(course.checkpoints),
                tiers,
                fallback,
                commandTiers,
                List.copyOf(course.fallbackCommands),
                new RewardPolicyInfo(course.rewardRequireNewBest, course.rewardOncePerTier, course.rewardDailyLimit)
        );
    }

    public static boolean hasCustomSpawns(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return false;
        }
        List<BlockPos> list = course.customSpawns.get(world.dimension());
        return list != null && !list.isEmpty();
    }

    public static void openSpawnListScreen(ServerLevel world, ServerPlayer player, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        List<BlockPos> coords = getCustomSpawns(world, courseId);
        if (coords.isEmpty()) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.spawn_list.empty").withStyle(ChatFormatting.GRAY), false);
            return;
        }

        String setCommandPrefix = commandPrefix(courseId, "area set ");
        String removeCommandPrefix = commandPrefix(courseId, "area remove ");

        sendPlayerMessage(player, divider(), false);
        sendPlayerMessage(player, Component.translatable("quest.wolkensprung.spawn_list.header", coords.size()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        for (BlockPos pos : coords) {
            String coordString = pos.getX() + " " + pos.getY() + " " + pos.getZ();
            Component set = Component.literal("[S]").withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent.SuggestCommand(setCommandPrefix + coordString)));
            Component delete = Component.literal(" [X]").withStyle(style -> style.withColor(ChatFormatting.RED)
                    .withClickEvent(new ClickEvent.RunCommand(removeCommandPrefix + coordString)));
            sendPlayerMessage(player, Component.empty()
                    .append(set)
                    .append(Component.literal(" " + coordString).withStyle(ChatFormatting.GRAY))
                    .append(delete), false);
        }
        sendPlayerMessage(player, divider(), false);
    }

    public static boolean addCustomSpawnChecked(ServerLevel world, ServerPlayer player, String rawCourseId, BlockPos pos) {
        String courseId = normalizeCourseId(rawCourseId);
        BlockPos immutablePos = pos.immutable();
        List<BlockPos> list = getOrCreatePositionList(getOrCreateCourse(courseId).customSpawns, world.dimension());
        if (list.contains(immutablePos)) {
            sendPlayerMessage(player, Component.empty()
                    .append(Component.translatable("quest.wolkensprung.spawn_exists").withStyle(ChatFormatting.RED))
                    .append(formatPos(immutablePos)), false);
            return false;
        }

        Component validation = validateSpawnSpot(world, immutablePos);
        if (validation != null) {
            sendPlayerMessage(player, validation, false);
            return false;
        }

        list.add(immutablePos);
        markDirty(world);
        return true;
    }

    public static boolean removeCustomSpawn(ServerLevel world, String rawCourseId, BlockPos pos) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return false;
        }
        List<BlockPos> list = course.customSpawns.get(world.dimension());
        if (list == null || list.isEmpty()) {
            return false;
        }

        boolean removed = list.remove(pos);
        if (removed) {
            if (list.isEmpty()) {
                course.customSpawns.remove(world.dimension());
            }
            markDirty(world);
        }
        return removed;
    }

    public static int getCustomSpawnCount(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        List<BlockPos> list = course.customSpawns.get(world.dimension());
        return list == null ? 0 : list.size();
    }

    public static int clearCustomSpawns(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        List<BlockPos> list = course.customSpawns.remove(world.dimension());
        markDirty(world);
        return list == null ? 0 : list.size();
    }

    public static List<BlockPos> getCustomSpawns(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return List.of();
        }
        List<BlockPos> list = course.customSpawns.get(world.dimension());
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public static List<BlockPos> getCheckpoints(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return List.of();
        }
        List<BlockPos> list = course.checkpoints.get(world.dimension());
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public static boolean addCheckpointManual(ServerLevel world, String rawCourseId, BlockPos pos) {
        String courseId = normalizeCourseId(rawCourseId);
        List<BlockPos> list = getOrCreatePositionList(getOrCreateCourse(courseId).checkpoints, world.dimension());
        BlockPos immutablePos = pos.immutable();
        if (list.contains(immutablePos)) {
            return false;
        }
        list.add(immutablePos);
        markDirty(world);
        return true;
    }

    public static boolean removeCheckpoint(ServerLevel world, String rawCourseId, BlockPos pos) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return false;
        }
        List<BlockPos> list = course.checkpoints.get(world.dimension());
        if (list == null || list.isEmpty()) {
            return false;
        }

        boolean removed = list.remove(pos);
        if (removed) {
            if (list.isEmpty()) {
                course.checkpoints.remove(world.dimension());
            }
            markDirty(world);
        }
        return removed;
    }

    public static int clearCheckpoints(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        List<BlockPos> list = course.checkpoints.remove(world.dimension());
        markDirty(world);
        return list == null ? 0 : list.size();
    }

    public static boolean toggleWand(UUID playerId, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        String current = AREA_WAND_COURSE.get(playerId);
        if (courseId.equals(current)) {
            AREA_WAND_COURSE.remove(playerId);
            return false;
        }
        AREA_WAND_COURSE.put(playerId, courseId);
        return true;
    }

    public static boolean toggleCheckpointWand(UUID playerId, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        String current = CHECKPOINT_WAND_COURSE.get(playerId);
        if (courseId.equals(current)) {
            CHECKPOINT_WAND_COURSE.remove(playerId);
            return false;
        }
        CHECKPOINT_WAND_COURSE.put(playerId, courseId);
        return true;
    }

    public static void setRespawn(ServerLevel world, String rawCourseId, BlockPos pos) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.respawnDim = world.dimension();
        course.respawnPos = pos.immutable();
        markDirty(world);
    }

    public static void clearRespawn(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return;
        }
        course.respawnPos = null;
        course.respawnDim = null;
        markDirty(world);
    }

    public static BlockPos getRespawnPos(String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        return course == null ? null : course.respawnPos;
    }

    public static ResourceKey<Level> getRespawnDim(String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        return course == null ? null : course.respawnDim;
    }

    public static void setFallYThreshold(ServerLevel world, String rawCourseId, int y) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.fallYThreshold = y;
        markDirty(world);
    }

    public static int getFallYThreshold(String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        return course == null ? Integer.MIN_VALUE : course.fallYThreshold;
    }

    public static RewardTierInfo setRewardTier(ServerLevel world, String rawCourseId, int maxSeconds, String itemId, int count) {
        ItemReward reward = parseReward(itemId, count);
        if (reward == null) {
            return null;
        }

        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardTiers.removeIf(existing -> existing.maxSeconds() == maxSeconds);
        course.rewardTiers.add(new RewardTier(maxSeconds, reward));
        sortRewardTiers(course);
        markDirty(world);
        return new RewardTierInfo(maxSeconds, itemId(reward.item()), reward.count());
    }

    public static int clearRewardTiers(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        int removed = course.rewardTiers.size();
        course.rewardTiers.clear();
        markDirty(world);
        return removed;
    }

    public static RewardInfo setFallbackReward(ServerLevel world, String rawCourseId, String itemId, int count) {
        ItemReward reward = parseReward(itemId, count);
        if (reward == null) {
            return null;
        }

        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.fallbackReward = reward;
        markDirty(world);
        return new RewardInfo(itemId(reward.item()), reward.count());
    }

    public static boolean clearFallbackReward(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null || course.fallbackReward == null) {
            return false;
        }
        course.fallbackReward = null;
        markDirty(world);
        return true;
    }

    public static CommandRewardTierInfo addCommandRewardTier(ServerLevel world, String rawCourseId, int maxSeconds, String rawCommand) {
        String command = normalizeRewardCommand(rawCommand);
        if (command == null) {
            return null;
        }

        CourseConfig course = getOrCreateCourse(rawCourseId);
        CommandRewardTier tier = course.commandRewardTiers.stream()
                .filter(existing -> existing.maxSeconds() == maxSeconds)
                .findFirst()
                .orElse(null);
        if (tier == null) {
            tier = new CommandRewardTier(maxSeconds, Collections.synchronizedList(new ArrayList<>()));
            course.commandRewardTiers.add(tier);
        }
        if (!tier.commands().contains(command)) {
            tier.commands().add(command);
        }
        sortCommandRewardTiers(course);
        markDirty(world);
        return new CommandRewardTierInfo(maxSeconds, List.copyOf(tier.commands()));
    }

    public static int clearCommandRewardTiers(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        int removed = course.commandRewardTiers.size();
        course.commandRewardTiers.clear();
        markDirty(world);
        return removed;
    }

    public static boolean addFallbackCommand(ServerLevel world, String rawCourseId, String rawCommand) {
        String command = normalizeRewardCommand(rawCommand);
        if (command == null) {
            return false;
        }

        CourseConfig course = getOrCreateCourse(rawCourseId);
        if (course.fallbackCommands.contains(command)) {
            return true;
        }
        course.fallbackCommands.add(command);
        markDirty(world);
        return true;
    }

    public static int clearFallbackCommands(ServerLevel world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        int removed = course.fallbackCommands.size();
        course.fallbackCommands.clear();
        markDirty(world);
        return removed;
    }

    public static void setRewardRequireNewBest(ServerLevel world, String rawCourseId, boolean value) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardRequireNewBest = value;
        markDirty(world);
    }

    public static void setRewardOncePerTier(ServerLevel world, String rawCourseId, boolean value) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardOncePerTier = value;
        markDirty(world);
    }

    public static void setRewardDailyLimit(ServerLevel world, String rawCourseId, int value) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardDailyLimit = Math.max(0, value);
        markDirty(world);
    }

    public static Component activeQuestLine(UUID playerId) {
        ActiveRun run = ACTIVE_RUNS.get(playerId);
        if (run == null) {
            return kofferHintLine();
        }
        return activeChestHint(run);
    }

    public static boolean isQuestGiver(Entity entity) {
        if (entity instanceof QuestGiverEntity) {
            return true;
        }
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand statue) {
            return isQuestStatue(statue);
        }
        return false;
    }

    public static CompoundTag writeToNbt() {
        CompoundTag root = new CompoundTag();
        root.put("courses", writeCourses());
        root.put("activeRuns", writeActiveRuns());
        root.put("completedByPlayer", writeUuidCourseSetMap(COMPLETED_COURSES));
        root.put("discoveredByPlayer", writeUuidCourseSetMap(DISCOVERED_COURSES));
        root.put("personalBestTicks", writePersonalBestMap());
        root.put("dailyRewardState", writeDailyRewardState());
        root.put("claimedRewardKeys", writeClaimedRewardKeys());
        root.put("checkpointProgress", writeCheckpointProgress());
        return root;
    }

    public static void readFromNbt(CompoundTag root) {
        clearTransientState();
        COURSES.clear();
        ACTIVE_RUNS.clear();
        COMPLETED_COURSES.clear();
        DISCOVERED_COURSES.clear();
        PERSONAL_BEST_TICKS.clear();
        DAILY_REWARD_STATE.clear();
        CLAIMED_REWARD_KEYS.clear();
        CHECKPOINT_PROGRESS.clear();

        if (root == null || root.isEmpty()) {
            return;
        }

        readCourses(root.getListOrEmpty("courses"));
        readActiveRuns(root);
        readUuidCourseSetMap(root.getListOrEmpty("completedByPlayer"), COMPLETED_COURSES);
        readUuidCourseSetMap(root.getListOrEmpty("discoveredByPlayer"), DISCOVERED_COURSES);
        readPersonalBestMap(root.getListOrEmpty("personalBestTicks"));
        readDailyRewardState(root.getListOrEmpty("dailyRewardState"));
        readClaimedRewardKeys(root.getListOrEmpty("claimedRewardKeys"));
        readCheckpointProgress(root.getListOrEmpty("checkpointProgress"));

        migrateLegacyCourseData(root);
    }

    private static InteractionResult handleQuestDialog(ServerLevel world, ServerPlayer player, String courseId) {
        UUID playerId = player.getUUID();
        ActiveRun activeRun = ACTIVE_RUNS.get(playerId);
        if (activeRun != null) {
            sendPlayerMessage(player, activeChestHint(activeRun), false);
            return InteractionResult.SUCCESS;
        }

        if (!hasCustomSpawns(world, courseId)) {
            if (!throttleDialog(world, playerId, 10)) {
                sendPlayerMessage(player, Component.empty()
                        .append(errorTag())
                        .append(Component.translatable("quest.wolkensprung.no_spawns").withStyle(ChatFormatting.RED)), false);
                showSpawnSetup(world, player, courseId);
            }
            return InteractionResult.SUCCESS;
        }

        if (hasCompleted(playerId, courseId)) {
            if (!throttleDialog(world, playerId, 10)) {
                showReturnDialog(player, courseId);
            }
            return InteractionResult.SUCCESS;
        }

        if (!throttleDialog(world, playerId, 10)) {
            showOffer(player, courseId);
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean accept(ServerLevel world, ServerPlayer player, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        PENDING_OFFERS.remove(player.getUUID());
        markDiscovered(world, player.getUUID(), courseId);
        if (hasCompleted(player.getUUID(), courseId)) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.already_completed", questDisplayName(courseId)).withStyle(ChatFormatting.GRAY), false);
            return false;
        }
        return startRun(world, player, courseId, false);
    }

    private static boolean acceptTimer(ServerLevel world, ServerPlayer player, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        PENDING_OFFERS.remove(player.getUUID());
        if (!hasCompleted(player.getUUID(), courseId)) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.timer_locked").withStyle(ChatFormatting.RED), false);
            return false;
        }
        return startRun(world, player, courseId, true);
    }

    private static boolean startRun(ServerLevel world, ServerPlayer player, String courseId, boolean repeatRun) {
        UUID playerId = player.getUUID();
        clearPlayerCheckpoints(world, playerId);
        if (ACTIVE_RUNS.containsKey(playerId)) {
            sendPlayerMessage(player, activeChestHint(ACTIVE_RUNS.get(playerId)), false);
            return false;
        }

        BlockPos placed = placeChest(world, player, courseId);
        if (placed == null) {
            if (!hasCustomSpawns(world, courseId)) {
                sendPlayerMessage(player, Component.translatable("quest.wolkensprung.no_spawns").withStyle(ChatFormatting.RED), false);
            } else {
                sendPlayerMessage(player, Component.translatable("quest.wolkensprung.no_chest_space").withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        ACTIVE_RUNS.put(playerId, new ActiveRun(courseId, placed, world.dimension(), world.getGameTime(), repeatRun));
        markDirty(world);

        if (repeatRun) {
            sendPlayerMessage(player, Component.empty()
                    .append(Component.translatable("quest.wolkensprung.timer_started").withStyle(ChatFormatting.GRAY))
                    .append(kofferToken()), false);
            return true;
        }

        Component divider = divider();
        sendPlayerMessage(player, Component.empty()
                .append(Component.literal("\n"))
                .append(divider.copy()).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.accept.line1").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.accept.line2").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.accept.line3").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.accept.line4").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n\n"))
                .append(kofferHintLine()).append(Component.literal("\n"))
                .append(divider.copy())
                .append(Component.literal("\n\n")), false);
        return true;
    }

    private static void declineTimer(ServerPlayer player) {
        sendPlayerMessage(player, Component.translatable("quest.wolkensprung.timer_declined").withStyle(ChatFormatting.GRAY), false);
    }

    private static void declineOffer(ServerPlayer player) {
        sendPlayerMessage(player, Component.translatable("quest.wolkensprung.offer_declined").withStyle(ChatFormatting.GRAY), false);
    }

    private static void markDiscovered(ServerLevel world, UUID playerId, String rawCourseId) {
        addCourseFlag(DISCOVERED_COURSES, playerId, rawCourseId);
        markDirty(world);
    }

    private static void markDirty(ServerLevel world) {
        if (world != null) {
            WolkensprungState.get(world.getServer()).updateFromRuntime();
        }
    }

    private static void finish(ServerLevel world, ServerPlayer player, ActiveRun run) {
        UUID playerId = player.getUUID();
        String courseId = run.courseId();
        boolean firstCompletion = !hasCompleted(playerId, courseId);
        ACTIVE_RUNS.remove(playerId);
        world.destroyBlock(run.pos(), false, player);
        clearPlayerCheckpoints(world, playerId);

        long elapsedTicks = Math.max(0L, world.getGameTime() - run.startTick());
        if (firstCompletion) {
            addCourseFlag(COMPLETED_COURSES, playerId, courseId);
        }

        boolean newBest = updatePersonalBest(playerId, courseId, elapsedTicks);
        Long bestTicks = getPersonalBestTicks(playerId, courseId);
        ResolvedItemReward itemReward = resolveItemReward(courseId, elapsedTicks);
        ResolvedCommandReward commandReward = resolveCommandReward(courseId, elapsedTicks);
        boolean hadConfiguredReward = itemReward != null || commandReward != null;

        RewardBlockReason rewardBlockReason = null;
        CourseConfig course = getCourse(courseId);
        if (hadConfiguredReward && course != null) {
            if (course.rewardRequireNewBest && !newBest) {
                rewardBlockReason = RewardBlockReason.NEW_BEST_ONLY;
            } else if (course.rewardDailyLimit > 0 && !canClaimRewardToday(playerId, courseId, course.rewardDailyLimit)) {
                rewardBlockReason = RewardBlockReason.DAILY_LIMIT;
            }
        }

        if (rewardBlockReason == null && course != null && course.rewardOncePerTier) {
            if (itemReward != null && hasClaimedRewardKey(playerId, courseId, itemReward.claimKey())) {
                itemReward = null;
            }
            if (commandReward != null && hasClaimedRewardKey(playerId, courseId, commandReward.claimKey())) {
                commandReward = null;
            }
            if (hadConfiguredReward && itemReward == null && commandReward == null) {
                rewardBlockReason = RewardBlockReason.TIER_ALREADY_CLAIMED;
            }
        }

        if (rewardBlockReason == null) {
            if (itemReward != null) {
                giveReward(player, itemReward.reward());
                if (course != null && course.rewardOncePerTier) {
                    markRewardKeyClaimed(playerId, courseId, itemReward.claimKey());
                }
            }
            if (commandReward != null) {
                executeRewardCommands(world, player, courseId, elapsedTicks, commandReward.commands());
                if (course != null && course.rewardOncePerTier) {
                    markRewardKeyClaimed(playerId, courseId, commandReward.claimKey());
                }
            }
            if (course != null && course.rewardDailyLimit > 0 && (itemReward != null || commandReward != null)) {
                incrementDailyRewardCount(playerId, courseId);
            }
        }

        world.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9f, 1.0f);
        sendPlayerMessage(player, Component.translatable("quest.wolkensprung.time", formatSeconds(elapsedTicks)).withStyle(ChatFormatting.GREEN), false);
        if (itemReward != null) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.reward.line", rewardText(itemReward.reward())).withStyle(ChatFormatting.GREEN), false);
        }
        if (commandReward != null) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.reward.command_line").withStyle(ChatFormatting.GREEN), false);
        }
        if (rewardBlockReason != null) {
            sendPlayerMessage(player, rewardBlockedMessage(rewardBlockReason), false);
        }
        if (newBest && bestTicks != null) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.best.new", formatSeconds(bestTicks)).withStyle(ChatFormatting.AQUA), false);
        } else if (bestTicks != null) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.best.current", formatSeconds(bestTicks)).withStyle(ChatFormatting.GRAY), false);
        }
        sendPlayerMessage(player, Component.translatable(
                firstCompletion ? "quest.wolkensprung.completed" : "quest.wolkensprung.repeat_completed",
                questDisplayName(courseId)).withStyle(ChatFormatting.GREEN), false);
        markDirty(world);
    }

    private static void giveReward(ServerPlayer player, ItemReward reward) {
        int remaining = reward.count();
        int maxStack = Math.max(1, reward.item().getDefaultMaxStackSize());
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStack);
            player.getInventory().placeItemBackInInventory(new ItemStack(reward.item(), stackSize));
            remaining -= stackSize;
        }
    }

    private static void executeRewardCommands(ServerLevel world, ServerPlayer player, String rawCourseId, long elapsedTicks, List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        String courseId = normalizeCourseId(rawCourseId);
        for (String rawCommand : commands) {
            String command = normalizeRewardCommand(rawCommand);
            if (command == null) {
                continue;
            }
            command = applyRewardCommandPlaceholders(command, player, courseId, elapsedTicks);
            world.getServer().getCommands().performPrefixedCommand(
                    world.getServer().createCommandSourceStack().withSuppressedOutput(),
                    command
            );
        }
    }

    private static void showOffer(ServerPlayer player, String courseId) {
        PENDING_OFFERS.put(player.getUUID(), new PendingOffer(courseId, OfferType.MAIN));

        Component accept = Component.translatable("quest.wolkensprung.offer.accept").withStyle(style -> style
                .withColor(ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_ACCEPT_COMMAND)));
        Component decline = Component.translatable("quest.wolkensprung.offer.decline").withStyle(style -> style
                .withColor(ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_DECLINE_COMMAND)));

        sendPlayerMessage(player, Component.empty()
                .append(Component.literal("\n"))
                .append(divider().copy()).append(Component.literal("\n"))
                .append(questTitle(courseId)).append(Component.literal("\n\n"))
                .append(Component.translatable("quest.wolkensprung.offer.line1").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.offer.line2").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n\n"))
                .append(accept).append(Component.literal("\n"))
                .append(decline).append(Component.literal("\n"))
                .append(divider().copy())
                .append(Component.literal("\n\n")), false);
    }

    private static void showSpawnSetup(ServerLevel world, ServerPlayer player, String courseId) {
        BlockPos pos = player.blockPosition().below();
        String setCommand = commandPrefix(courseId, "area set ") + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String setHereCommand = commandPrefix(courseId, "area sethere");

        sendPlayerMessage(player, Component.empty()
                .append(Component.literal("\n"))
                .append(divider().copy()).append(Component.literal("\n"))
                .append(questTitle(courseId)).append(Component.literal("\n\n"))
                .append(Component.translatable("quest.wolkensprung.setup.info").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.setup.prompt").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"))
                .append(Component.literal("    " + setCommand).withStyle(style -> style
                        .withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.SuggestCommand(setCommand)))).append(Component.literal("\n"))
                .append(Component.translatable("quest.wolkensprung.setup.current").withStyle(style -> style
                        .withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.RunCommand(setHereCommand)))).append(Component.literal("\n"))
                .append(divider().copy())
                .append(Component.literal("\n\n")), false);
    }

    private static void showReturnDialog(ServerPlayer player, String courseId) {
        PENDING_OFFERS.put(player.getUUID(), new PendingOffer(courseId, OfferType.TIMER));

        Component accept = Component.translatable("quest.wolkensprung.return.accept").withStyle(style -> style
                .withColor(ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_ACCEPT_COMMAND)));
        Component decline = Component.translatable("quest.wolkensprung.return.decline").withStyle(style -> style
                .withColor(ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_DECLINE_COMMAND)));

        sendPlayerMessage(player, Component.empty()
                .append(Component.literal("\n"))
                .append(divider().copy()).append(Component.literal("\n"))
                .append(questTitle(courseId)).append(Component.literal("\n\n"))
                .append(Component.translatable("quest.wolkensprung.return.prompt").withStyle(ChatFormatting.GRAY)).append(Component.literal("\n\n"))
                .append(accept).append(Component.literal("\n"))
                .append(decline).append(Component.literal("\n"))
                .append(divider().copy())
                .append(Component.literal("\n\n")), false);
    }

    private static void teleportToRespawn(ServerPlayer player, ServerLevel targetWorld, String courseId) {
        if (!hasActive(player.getUUID())) {
            return;
        }

        BlockPos pos = getCheckpointRespawn(player, targetWorld, courseId);
        boolean usedCheckpoint = pos != null;
        if (pos == null) {
            CourseConfig course = getCourse(courseId);
            if (course == null) {
                return;
            }
            pos = course.respawnPos;
        }
        if (pos == null) {
            return;
        }

        player.fallDistance = 0.0f;
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.teleportTo(
                targetWorld,
                pos.getX() + 0.5,
                pos.getY() + 0.1,
                pos.getZ() + 0.5,
                EnumSet.noneOf(Relative.class),
                player.getYRot(),
                player.getXRot(),
                false
        );
        targetWorld.playSound(
                null,
                pos,
                usedCheckpoint ? SoundEvents.ENDER_PEARL_THROW : SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                usedCheckpoint ? 1.2f : 0.9f,
                1.0f
        );
    }

    private static boolean throttleDialog(ServerLevel world, UUID playerId, int minGapTicks) {
        long now = world.getGameTime();
        long last = LAST_DIALOG_TICK.getOrDefault(playerId, -minGapTicks - 1L);
        if ((now - last) < minGapTicks) {
            return true;
        }
        LAST_DIALOG_TICK.put(playerId, now);
        return false;
    }

    private static void warnElytraBlocked(ServerPlayer player, ServerLevel world) {
        UUID playerId = player.getUUID();
        long now = world.getGameTime();
        long last = LAST_ELYTRA_WARN.getOrDefault(playerId, -200L);
        if ((now - last) < 40L) {
            return;
        }
        LAST_ELYTRA_WARN.put(playerId, now);
        sendPlayerMessage(player, Component.translatable("quest.wolkensprung.elytra_blocked").withStyle(ChatFormatting.GRAY), false);
    }

    private static boolean isQuestStatue(net.minecraft.world.entity.decoration.ArmorStand statue) {
        if (!statue.hasCustomName()) {
            return false;
        }
        String name = statue.getName().getString().trim();
        return name.equalsIgnoreCase(QUEST_VILLAGER_NAME);
    }

    private static String getQuestGiverCourseId(Entity entity) {
        if (entity instanceof QuestGiverEntity questGiver) {
            return normalizeCourseId(questGiver.getCourseId());
        }
        return DEFAULT_COURSE_ID;
    }

    private static Component activeChestHint(ActiveRun run) {
        return Component.empty()
                .append(Component.translatable("quest.wolkensprung.chest_hint.prefix").withStyle(ChatFormatting.GRAY))
                .append(kofferToken())
                .append(Component.translatable("quest.wolkensprung.chest_hint.suffix").withStyle(ChatFormatting.GRAY));
    }

    private static Component divider() {
        return Component.literal("------------------------------").withStyle(ChatFormatting.GRAY);
    }

    private static Component questName() {
        return Component.translatable("quest.wolkensprung.name");
    }

    private static Component questTag() {
        return Component.translatable("ui.wolkensprung.quest_tag").withStyle(ChatFormatting.GRAY);
    }

    private static Component errorTag() {
        return Component.translatable("ui.wolkensprung.error_tag").withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent questTitle(String courseId) {
        MutableComponent title = Component.empty()
                .append(questTag())
                .append(questName().copy().withStyle(ChatFormatting.LIGHT_PURPLE));
        if (!DEFAULT_COURSE_ID.equals(courseId)) {
            title.append(Component.literal(" [" + courseId + "]").withStyle(ChatFormatting.YELLOW));
        }
        return title;
    }

    private static MutableComponent questDisplayName(String courseId) {
        MutableComponent display = questName().copy();
        if (!DEFAULT_COURSE_ID.equals(courseId)) {
            display.append(Component.literal(" [" + courseId + "]").withStyle(ChatFormatting.YELLOW));
        }
        return display;
    }

    private static Component formatPos(BlockPos pos) {
        return Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.YELLOW);
    }

    private static Component kofferToken() {
        return Component.translatable("quest.wolkensprung.token").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("tooltip.wolkensprung.token").withStyle(ChatFormatting.GRAY))));
    }

    private static Component kofferHintLine() {
        return Component.empty()
                .append(Component.translatable("quest.wolkensprung.active.prefix").withStyle(ChatFormatting.GRAY))
                .append(kofferToken())
                .append(Component.translatable("quest.wolkensprung.active.suffix").withStyle(ChatFormatting.GRAY));
    }

    private static void addCheckpoint(ServerLevel world, ServerPlayer player, String rawCourseId, BlockPos pos) {
        String courseId = normalizeCourseId(rawCourseId);
        List<BlockPos> list = getOrCreatePositionList(getOrCreateCourse(courseId).checkpoints, world.dimension());
        BlockPos immutablePos = pos.immutable();
        if (list.contains(immutablePos)) {
            sendPlayerMessage(player, Component.translatable("quest.wolkensprung.checkpoint.exists").withStyle(ChatFormatting.RED), false);
            return;
        }
        list.add(immutablePos);
        markDirty(world);
        sendPlayerMessage(player, Component.translatable("quest.wolkensprung.checkpoint.set_total", list.size()).withStyle(ChatFormatting.GRAY), false);
    }

    private static void updateCheckpointProgress(ServerLevel world, ServerPlayer player, ActiveRun run) {
        CourseConfig course = getCourse(run.courseId());
        if (course == null) {
            return;
        }

        List<BlockPos> list = course.checkpoints.get(world.dimension());
        if (list == null || list.isEmpty() || !player.onGround()) {
            return;
        }

        BlockPos playerPos = player.blockPosition().below();
        ActiveCheckpoint progress = CHECKPOINT_PROGRESS.get(player.getUUID());
        int currentIndex = -1;
        if (progress != null && run.courseId().equals(progress.courseId()) && world.dimension().equals(progress.dimension())) {
            currentIndex = progress.index();
        }

        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).equals(playerPos)) {
                continue;
            }
            if (i > currentIndex) {
                CHECKPOINT_PROGRESS.put(player.getUUID(), new ActiveCheckpoint(run.courseId(), i, world.dimension()));
                markDirty(world);
                sendPlayerMessage(player, Component.translatable("quest.wolkensprung.checkpoint.reached").withStyle(ChatFormatting.GRAY), false);
            }
            return;
        }
    }

    private static BlockPos getCheckpointRespawn(ServerPlayer player, ServerLevel world, String courseId) {
        ActiveCheckpoint checkpoint = CHECKPOINT_PROGRESS.get(player.getUUID());
        if (checkpoint == null) {
            return null;
        }
        if (!normalizeCourseId(courseId).equals(checkpoint.courseId()) || !world.dimension().equals(checkpoint.dimension())) {
            return null;
        }

        CourseConfig course = getCourse(courseId);
        if (course == null) {
            return null;
        }
        List<BlockPos> list = course.checkpoints.get(world.dimension());
        if (list == null || checkpoint.index() < 0 || checkpoint.index() >= list.size()) {
            return null;
        }
        return list.get(checkpoint.index()).above();
    }

    private static void clearPlayerCheckpoints(ServerLevel world, UUID playerId) {
        CHECKPOINT_PROGRESS.remove(playerId);
        markDirty(world);
    }

    private static BlockPos placeChest(ServerLevel world, ServerPlayer player, String courseId) {
        return tryPlaceFromCustomList(world, player, courseId);
    }

    private static BlockPos tryPlaceFromCustomList(ServerLevel world, ServerPlayer player, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return null;
        }
        List<BlockPos> list = course.customSpawns.get(world.dimension());
        if (list == null || list.isEmpty()) {
            return null;
        }

        List<BlockPos> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);
        List<BlockPos> toRemove = new ArrayList<>();
        Component lastFailure = null;

        for (BlockPos pos : shuffled) {
            Component validation = validateSpawnSpot(world, pos);
            if (validation != null) {
                toRemove.add(pos);
                lastFailure = validation;
                continue;
            }

            TryPlaceResult result = tryPlaceChestOnTop(world, player, pos);
            if (result.success()) {
                return result.placedPos();
            }
            lastFailure = result.failureMessage();
        }

        if (!toRemove.isEmpty()) {
            list.removeAll(toRemove);
            if (list.isEmpty()) {
                course.customSpawns.remove(world.dimension());
            }
            markDirty(world);
        }
        if (lastFailure != null) {
            sendPlayerMessage(player, lastFailure, false);
        }
        return null;
    }

    private static TryPlaceResult tryPlaceChestOnTop(ServerLevel world, ServerPlayer player, BlockPos basePos) {
        BlockState baseState = world.getBlockState(basePos);
        if (baseState.is(BlockTags.LOGS) || !hasFlatTop(world, basePos, baseState) || !world.isEmptyBlock(basePos.above())) {
            return TryPlaceResult.fail(Component.empty()
                    .append(Component.translatable("quest.wolkensprung.spawn.invalid_generic").withStyle(ChatFormatting.RED))
                    .append(formatPos(basePos)));
        }

        BlockPos chestPos = basePos.above();
        BlockState chestState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, player.getDirection().getOpposite());
        world.setBlockAndUpdate(chestPos, chestState);
        return TryPlaceResult.success(chestPos);
    }

    private static void emitChestBeam(ServerLevel world, ServerPlayer player, BlockPos chestPos) {
        double x = chestPos.getX() + 0.5;
        double z = chestPos.getZ() + 0.5;
        for (int i = 0; i < 10; i++) {
            double y = chestPos.getY() + 0.35 + (i * 0.55);
            world.sendParticles(player, ParticleTypes.END_ROD, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static Component validateSpawnSpot(ServerLevel world, BlockPos basePos) {
        BlockState baseState = world.getBlockState(basePos);
        if (baseState.is(BlockTags.LOGS)) {
            return Component.empty()
                    .append(Component.translatable("quest.wolkensprung.spawn.invalid_log").withStyle(ChatFormatting.RED))
                    .append(formatPos(basePos));
        }
        if (!hasFlatTop(world, basePos, baseState)) {
            return Component.empty()
                    .append(Component.translatable("quest.wolkensprung.spawn.invalid_flat").withStyle(ChatFormatting.RED))
                    .append(formatPos(basePos));
        }
        if (!world.isEmptyBlock(basePos.above())) {
            return Component.empty()
                    .append(Component.translatable("quest.wolkensprung.spawn.invalid_blocked").withStyle(ChatFormatting.RED))
                    .append(formatPos(basePos));
        }
        return null;
    }

    private static boolean hasFlatTop(ServerLevel world, BlockPos pos, BlockState state) {
        return Block.isShapeFullBlock(state.getCollisionShape(world, pos));
    }

    private static boolean updatePersonalBest(UUID playerId, String rawCourseId, long ticks) {
        String courseId = normalizeCourseId(rawCourseId);
        Map<String, Long> bests = PERSONAL_BEST_TICKS.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        Long previous = bests.get(courseId);
        if (previous != null && previous <= ticks) {
            return false;
        }
        bests.put(courseId, ticks);
        return true;
    }

    private static Long getPersonalBestTicks(UUID playerId, String rawCourseId) {
        Map<String, Long> bests = PERSONAL_BEST_TICKS.get(playerId);
        if (bests == null) {
            return null;
        }
        return bests.get(normalizeCourseId(rawCourseId));
    }

    private static ResolvedItemReward resolveItemReward(String rawCourseId, long elapsedTicks) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return null;
        }

        for (RewardTier tier : course.rewardTiers.stream().sorted(Comparator.comparingInt(RewardTier::maxSeconds)).toList()) {
            if (elapsedTicks <= secondsToTicks(tier.maxSeconds())) {
                return new ResolvedItemReward(tier.reward(), "item:" + tier.maxSeconds(), false);
            }
        }

        if (course.fallbackReward != null) {
            return new ResolvedItemReward(course.fallbackReward, "item:fallback", true);
        }
        return null;
    }

    private static ResolvedCommandReward resolveCommandReward(String rawCourseId, long elapsedTicks) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return null;
        }

        for (CommandRewardTier tier : course.commandRewardTiers.stream().sorted(Comparator.comparingInt(CommandRewardTier::maxSeconds)).toList()) {
            if (elapsedTicks <= secondsToTicks(tier.maxSeconds()) && !tier.commands().isEmpty()) {
                return new ResolvedCommandReward(List.copyOf(tier.commands()), "command:" + tier.maxSeconds(), false);
            }
        }

        if (!course.fallbackCommands.isEmpty()) {
            return new ResolvedCommandReward(List.copyOf(course.fallbackCommands), "command:fallback", true);
        }
        return null;
    }

    private static MutableComponent rewardBlockedMessage(RewardBlockReason reason) {
        return switch (reason) {
            case NEW_BEST_ONLY -> Component.translatable("quest.wolkensprung.reward.blocked.new_best_only").withStyle(ChatFormatting.GRAY);
            case DAILY_LIMIT -> Component.translatable("quest.wolkensprung.reward.blocked.daily_limit").withStyle(ChatFormatting.GRAY);
            case TIER_ALREADY_CLAIMED -> Component.translatable("quest.wolkensprung.reward.blocked.tier_claimed").withStyle(ChatFormatting.GRAY);
        };
    }

    private static ItemReward parseReward(String rawItemId, int count) {
        if (rawItemId == null || rawItemId.isBlank() || count <= 0) {
            return null;
        }

        Identifier itemId;
        try {
            itemId = Identifier.parse(rawItemId);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
            return null;
        }

        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item == Items.AIR) {
            return null;
        }
        return new ItemReward(item, count);
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Component rewardText(ItemReward reward) {
        return Component.empty()
                .append(Component.literal(Integer.toString(reward.count())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("x ").withStyle(ChatFormatting.GRAY))
                .append(reward.item().getName(reward.item().getDefaultInstance()).copy().withStyle(ChatFormatting.AQUA));
    }

    private static String normalizeRewardCommand(String rawCommand) {
        if (rawCommand == null) {
            return null;
        }
        String command = rawCommand.trim();
        if (command.isEmpty()) {
            return null;
        }
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static String applyRewardCommandPlaceholders(String command, ServerPlayer player, String courseId, long elapsedTicks) {
        return command
                .replace("%player%", player.getScoreboardName())
                .replace("%uuid%", player.getUUID().toString())
                .replace("%course%", normalizeCourseId(courseId))
                .replace("%seconds%", formatSeconds(elapsedTicks));
    }

    private static boolean canClaimRewardToday(UUID playerId, String rawCourseId, int limit) {
        if (limit <= 0) {
            return true;
        }
        RewardDailyState state = getDailyRewardState(playerId, rawCourseId);
        if (state == null) {
            return true;
        }
        String today = LocalDate.now().toString();
        if (!today.equals(state.date())) {
            return true;
        }
        return state.count() < limit;
    }

    private static RewardDailyState getDailyRewardState(UUID playerId, String rawCourseId) {
        Map<String, RewardDailyState> byCourse = DAILY_REWARD_STATE.get(playerId);
        if (byCourse == null) {
            return null;
        }
        return byCourse.get(normalizeCourseId(rawCourseId));
    }

    private static void incrementDailyRewardCount(UUID playerId, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        String today = LocalDate.now().toString();
        Map<String, RewardDailyState> byCourse = DAILY_REWARD_STATE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        RewardDailyState current = byCourse.get(courseId);
        if (current == null || !today.equals(current.date())) {
            byCourse.put(courseId, new RewardDailyState(today, 1));
            return;
        }
        byCourse.put(courseId, new RewardDailyState(today, current.count() + 1));
    }

    private static boolean hasClaimedRewardKey(UUID playerId, String rawCourseId, String key) {
        Map<String, Set<String>> byCourse = CLAIMED_REWARD_KEYS.get(playerId);
        if (byCourse == null) {
            return false;
        }
        Set<String> keys = byCourse.get(normalizeCourseId(rawCourseId));
        return keys != null && keys.contains(key);
    }

    private static void markRewardKeyClaimed(UUID playerId, String rawCourseId, String key) {
        CLAIMED_REWARD_KEYS
                .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(normalizeCourseId(rawCourseId), ignored -> ConcurrentHashMap.newKeySet())
                .add(key);
    }

    private static String formatSeconds(long ticks) {
        return String.format(Locale.ROOT, "%.2f", ticks / 20.0);
    }

    private static long secondsToTicks(int seconds) {
        return Math.max(0L, (long) seconds * 20L);
    }

    private static void addCourseFlag(Map<UUID, Set<String>> map, UUID playerId, String rawCourseId) {
        if (playerId == null) {
            return;
        }
        map.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(normalizeCourseId(rawCourseId));
    }

    private static CourseConfig getCourse(String rawCourseId) {
        return COURSES.get(normalizeCourseId(rawCourseId));
    }

    private static CourseConfig getOrCreateCourse(String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        return COURSES.computeIfAbsent(courseId, CourseConfig::new);
    }

    private static List<BlockPos> getOrCreatePositionList(Map<ResourceKey<Level>, List<BlockPos>> map, ResourceKey<Level> dimension) {
        return map.computeIfAbsent(dimension, ignored -> Collections.synchronizedList(new ArrayList<>()));
    }

    private static int countPositions(Map<ResourceKey<Level>, List<BlockPos>> map) {
        int count = 0;
        for (List<BlockPos> list : map.values()) {
            count += list.size();
        }
        return count;
    }

    private static void sortRewardTiers(CourseConfig course) {
        course.rewardTiers.sort(Comparator.comparingInt(RewardTier::maxSeconds));
    }

    private static void sortCommandRewardTiers(CourseConfig course) {
        course.commandRewardTiers.sort(Comparator.comparingInt(CommandRewardTier::maxSeconds));
    }

    private static String commandPrefix(String courseId, String suffix) {
        if (DEFAULT_COURSE_ID.equals(courseId)) {
            return "/wolkensprung " + suffix;
        }
        return "/wolkensprung course " + courseId + " " + suffix;
    }

    private static ListTag writeCourses() {
        ListTag list = new ListTag();
        for (CourseConfig course : COURSES.values()) {
            CompoundTag item = new CompoundTag();
            item.putString("id", course.id);
            if (course.respawnPos != null && course.respawnDim != null) {
                CompoundTag respawn = new CompoundTag();
                respawn.putString("dim", course.respawnDim.identifier().toString());
                respawn.put("pos", posToNbt(course.respawnPos));
                item.put("respawn", respawn);
            }
            if (course.fallYThreshold != Integer.MIN_VALUE) {
                item.putInt("fallY", course.fallYThreshold);
            }
            item.put("customSpawns", writeDimPosMap(course.customSpawns));
            item.put("checkpoints", writeDimPosMap(course.checkpoints));
            item.put("rewardTiers", writeRewardTiers(course.rewardTiers));
            item.put("commandRewardTiers", writeCommandRewardTiers(course.commandRewardTiers));
            if (course.fallbackReward != null) {
                item.put("fallbackReward", writeReward(course.fallbackReward));
            }
            if (!course.fallbackCommands.isEmpty()) {
                item.put("fallbackCommands", writeStringList(course.fallbackCommands));
            }
            item.putBoolean("rewardRequireNewBest", course.rewardRequireNewBest);
            item.putBoolean("rewardOncePerTier", course.rewardOncePerTier);
            item.putInt("rewardDailyLimit", course.rewardDailyLimit);
            list.add(item);
        }
        return list;
    }

    private static void readCourses(ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            String courseId = normalizeCourseId(item.getStringOr("id", DEFAULT_COURSE_ID));
            CourseConfig course = getOrCreateCourse(courseId);

            CompoundTag respawn = item.getCompoundOrEmpty("respawn");
            if (!respawn.isEmpty()) {
                ResourceKey<Level> dim = parseWorldKey(respawn.getStringOr("dim", ""));
                BlockPos pos = posFromNbt(respawn.getCompoundOrEmpty("pos"));
                if (dim != null && pos != null) {
                    course.respawnDim = dim;
                    course.respawnPos = pos;
                }
            }

            if (item.contains("fallY")) {
                course.fallYThreshold = item.getIntOr("fallY", Integer.MIN_VALUE);
            }

            readDimPosMap(item.getListOrEmpty("customSpawns"), course.customSpawns);
            readDimPosMap(item.getListOrEmpty("checkpoints"), course.checkpoints);
            readRewardTiers(item.getListOrEmpty("rewardTiers"), course.rewardTiers);
            readCommandRewardTiers(item.getListOrEmpty("commandRewardTiers"), course.commandRewardTiers);
            course.fallbackReward = readReward(item.getCompoundOrEmpty("fallbackReward"));
            readStringList(item.getListOrEmpty("fallbackCommands"), course.fallbackCommands);
            course.rewardRequireNewBest = item.getBooleanOr("rewardRequireNewBest", false);
            course.rewardOncePerTier = item.getBooleanOr("rewardOncePerTier", false);
            course.rewardDailyLimit = item.getIntOr("rewardDailyLimit", 0);
            sortRewardTiers(course);
            sortCommandRewardTiers(course);
        }
    }

    private static ListTag writeActiveRuns() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ActiveRun> entry : ACTIVE_RUNS.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveRun run = entry.getValue();
            if (playerId == null || run == null || run.dimension() == null || run.pos() == null) {
                continue;
            }
            CompoundTag item = new CompoundTag();
            item.putString("id", playerId.toString());
            item.putString("courseId", run.courseId());
            item.putString("dim", run.dimension().identifier().toString());
            item.put("pos", posToNbt(run.pos()));
            item.putLong("startTick", run.startTick());
            item.putBoolean("repeatRun", run.repeatRun());
            list.add(item);
        }
        return list;
    }

    private static void readActiveRuns(CompoundTag root) {
        ListTag activeRuns = root.getListOrEmpty("activeRuns");
        for (int i = 0; i < activeRuns.size(); i++) {
            CompoundTag item = activeRuns.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            String courseId = normalizeCourseId(item.getStringOr("courseId", DEFAULT_COURSE_ID));
            ResourceKey<Level> dim = parseWorldKey(item.getStringOr("dim", ""));
            BlockPos pos = posFromNbt(item.getCompoundOrEmpty("pos"));
            long startTick = item.getLongOr("startTick", 0L);
            boolean repeatRun = item.getBooleanOr("repeatRun", false);
            if (playerId != null && dim != null && pos != null) {
                ACTIVE_RUNS.put(playerId, new ActiveRun(courseId, pos, dim, startTick, repeatRun));
            }
        }
    }

    private static ListTag writeUuidCourseSetMap(Map<UUID, Set<String>> map) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Set<String>> entry : map.entrySet()) {
            UUID playerId = entry.getKey();
            Set<String> courses = entry.getValue();
            if (playerId == null || courses == null || courses.isEmpty()) {
                continue;
            }
            CompoundTag item = new CompoundTag();
            item.putString("id", playerId.toString());
            ListTag coursesList = new ListTag();
            for (String courseId : new HashSet<>(courses)) {
                CompoundTag course = new CompoundTag();
                course.putString("courseId", courseId);
                coursesList.add(course);
            }
            item.put("courses", coursesList);
            list.add(item);
        }
        return list;
    }

    private static void readUuidCourseSetMap(ListTag list, Map<UUID, Set<String>> target) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            if (playerId == null) {
                continue;
            }
            Set<String> courses = target.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
            ListTag coursesList = item.getListOrEmpty("courses");
            for (int c = 0; c < coursesList.size(); c++) {
                String courseId = normalizeCourseId(coursesList.getCompoundOrEmpty(c).getStringOr("courseId", DEFAULT_COURSE_ID));
                courses.add(courseId);
            }
        }
    }

    private static ListTag writePersonalBestMap() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Map<String, Long>> entry : PERSONAL_BEST_TICKS.entrySet()) {
            UUID playerId = entry.getKey();
            Map<String, Long> bests = entry.getValue();
            if (playerId == null || bests == null || bests.isEmpty()) {
                continue;
            }

            CompoundTag item = new CompoundTag();
            item.putString("id", playerId.toString());
            ListTag bestList = new ListTag();
            for (Map.Entry<String, Long> bestEntry : bests.entrySet()) {
                CompoundTag best = new CompoundTag();
                best.putString("courseId", normalizeCourseId(bestEntry.getKey()));
                best.putLong("ticks", bestEntry.getValue());
                bestList.add(best);
            }
            item.put("best", bestList);
            list.add(item);
        }
        return list;
    }

    private static void readPersonalBestMap(ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            if (playerId == null) {
                continue;
            }

            Map<String, Long> bests = PERSONAL_BEST_TICKS.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            ListTag bestList = item.getListOrEmpty("best");
            for (int b = 0; b < bestList.size(); b++) {
                CompoundTag best = bestList.getCompoundOrEmpty(b);
                bests.put(normalizeCourseId(best.getStringOr("courseId", DEFAULT_COURSE_ID)), best.getLongOr("ticks", 0L));
            }
        }
    }

    private static ListTag writeCheckpointProgress() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ActiveCheckpoint> entry : CHECKPOINT_PROGRESS.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveCheckpoint checkpoint = entry.getValue();
            if (playerId == null || checkpoint == null || checkpoint.dimension() == null || checkpoint.index() < 0) {
                continue;
            }

            CompoundTag item = new CompoundTag();
            item.putString("id", playerId.toString());
            item.putString("courseId", checkpoint.courseId());
            item.putInt("index", checkpoint.index());
            item.putString("dim", checkpoint.dimension().identifier().toString());
            list.add(item);
        }
        return list;
    }

    private static void readCheckpointProgress(ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            ResourceKey<Level> dim = parseWorldKey(item.getStringOr("dim", ""));
            int index = item.getIntOr("index", -1);
            String courseId = normalizeCourseId(item.getStringOr("courseId", DEFAULT_COURSE_ID));
            if (playerId != null && dim != null && index >= 0) {
                CHECKPOINT_PROGRESS.put(playerId, new ActiveCheckpoint(courseId, index, dim));
            }
        }
    }

    private static ListTag writeDimPosMap(Map<ResourceKey<Level>, List<BlockPos>> map) {
        ListTag list = new ListTag();
        for (Map.Entry<ResourceKey<Level>, List<BlockPos>> entry : map.entrySet()) {
            ResourceKey<Level> dim = entry.getKey();
            if (dim == null) {
                continue;
            }
            CompoundTag item = new CompoundTag();
            item.putString("dim", dim.identifier().toString());
            ListTag posList = new ListTag();
            for (BlockPos pos : entry.getValue()) {
                posList.add(posToNbt(pos));
            }
            item.put("list", posList);
            list.add(item);
        }
        return list;
    }

    private static void readDimPosMap(ListTag list, Map<ResourceKey<Level>, List<BlockPos>> map) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            ResourceKey<Level> dim = parseWorldKey(item.getStringOr("dim", ""));
            if (dim == null) {
                continue;
            }

            ListTag posList = item.getListOrEmpty("list");
            List<BlockPos> positions = Collections.synchronizedList(new ArrayList<>());
            for (int p = 0; p < posList.size(); p++) {
                BlockPos pos = posFromNbt(posList.getCompoundOrEmpty(p));
                if (pos != null) {
                    positions.add(pos);
                }
            }
            if (!positions.isEmpty()) {
                map.put(dim, positions);
            }
        }
    }

    private static ListTag writeRewardTiers(List<RewardTier> tiers) {
        ListTag list = new ListTag();
        for (RewardTier tier : tiers.stream().sorted(Comparator.comparingInt(RewardTier::maxSeconds)).toList()) {
            CompoundTag item = new CompoundTag();
            item.putInt("maxSeconds", tier.maxSeconds());
            item.put("reward", writeReward(tier.reward()));
            list.add(item);
        }
        return list;
    }

    private static void readRewardTiers(ListTag list, List<RewardTier> target) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            int maxSeconds = item.getIntOr("maxSeconds", -1);
            ItemReward reward = readReward(item.getCompoundOrEmpty("reward"));
            if (maxSeconds >= 0 && reward != null) {
                target.add(new RewardTier(maxSeconds, reward));
            }
        }
    }

    private static CompoundTag writeReward(ItemReward reward) {
        CompoundTag item = new CompoundTag();
        item.putString("itemId", itemId(reward.item()));
        item.putInt("count", reward.count());
        return item;
    }

    private static ItemReward readReward(CompoundTag item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return parseReward(item.getStringOr("itemId", ""), item.getIntOr("count", 0));
    }

    private static ListTag writeDailyRewardState() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Map<String, RewardDailyState>> entry : DAILY_REWARD_STATE.entrySet()) {
            UUID playerId = entry.getKey();
            Map<String, RewardDailyState> byCourse = entry.getValue();
            if (playerId == null || byCourse == null || byCourse.isEmpty()) {
                continue;
            }

            CompoundTag item = new CompoundTag();
            item.putString("id", playerId.toString());
            ListTag courses = new ListTag();
            for (Map.Entry<String, RewardDailyState> courseEntry : byCourse.entrySet()) {
                RewardDailyState state = courseEntry.getValue();
                if (state == null || state.date() == null || state.date().isBlank() || state.count() < 0) {
                    continue;
                }
                CompoundTag course = new CompoundTag();
                course.putString("course", normalizeCourseId(courseEntry.getKey()));
                course.putString("date", state.date());
                course.putInt("count", state.count());
                courses.add(course);
            }
            if (!courses.isEmpty()) {
                item.put("courses", courses);
                list.add(item);
            }
        }
        return list;
    }

    private static void readDailyRewardState(ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            if (playerId == null) {
                continue;
            }

            Map<String, RewardDailyState> byCourse = DAILY_REWARD_STATE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            ListTag courses = item.getListOrEmpty("courses");
            for (int j = 0; j < courses.size(); j++) {
                CompoundTag courseItem = courses.getCompoundOrEmpty(j);
                String courseId = normalizeCourseId(courseItem.getStringOr("course", DEFAULT_COURSE_ID));
                String date = courseItem.getStringOr("date", "");
                int count = courseItem.getIntOr("count", -1);
                if (!date.isBlank() && count >= 0) {
                    byCourse.put(courseId, new RewardDailyState(date, count));
                }
            }
        }
    }

    private static ListTag writeClaimedRewardKeys() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Map<String, Set<String>>> entry : CLAIMED_REWARD_KEYS.entrySet()) {
            UUID playerId = entry.getKey();
            Map<String, Set<String>> byCourse = entry.getValue();
            if (playerId == null || byCourse == null || byCourse.isEmpty()) {
                continue;
            }

            CompoundTag item = new CompoundTag();
            item.putString("id", playerId.toString());
            ListTag courses = new ListTag();
            for (Map.Entry<String, Set<String>> courseEntry : byCourse.entrySet()) {
                Set<String> keys = courseEntry.getValue();
                if (keys == null || keys.isEmpty()) {
                    continue;
                }
                CompoundTag course = new CompoundTag();
                course.putString("course", normalizeCourseId(courseEntry.getKey()));
                course.put("keys", writeStringList(new ArrayList<>(keys)));
                courses.add(course);
            }
            if (!courses.isEmpty()) {
                item.put("courses", courses);
                list.add(item);
            }
        }
        return list;
    }

    private static void readClaimedRewardKeys(ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            if (playerId == null) {
                continue;
            }

            Map<String, Set<String>> byCourse = CLAIMED_REWARD_KEYS.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            ListTag courses = item.getListOrEmpty("courses");
            for (int j = 0; j < courses.size(); j++) {
                CompoundTag courseItem = courses.getCompoundOrEmpty(j);
                String courseId = normalizeCourseId(courseItem.getStringOr("course", DEFAULT_COURSE_ID));
                List<String> keys = new ArrayList<>();
                readStringList(courseItem.getListOrEmpty("keys"), keys);
                if (!keys.isEmpty()) {
                    byCourse.put(courseId, ConcurrentHashMap.newKeySet());
                    byCourse.get(courseId).addAll(keys);
                }
            }
        }
    }

    private static ListTag writeCommandRewardTiers(List<CommandRewardTier> tiers) {
        ListTag list = new ListTag();
        for (CommandRewardTier tier : tiers.stream().sorted(Comparator.comparingInt(CommandRewardTier::maxSeconds)).toList()) {
            CompoundTag item = new CompoundTag();
            item.putInt("maxSeconds", tier.maxSeconds());
            item.put("commands", writeStringList(tier.commands()));
            list.add(item);
        }
        return list;
    }

    private static void readCommandRewardTiers(ListTag list, List<CommandRewardTier> target) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            int maxSeconds = item.getIntOr("maxSeconds", -1);
            List<String> commands = new ArrayList<>();
            readStringList(item.getListOrEmpty("commands"), commands);
            if (maxSeconds >= 0 && !commands.isEmpty()) {
                target.add(new CommandRewardTier(maxSeconds, Collections.synchronizedList(new ArrayList<>(commands))));
            }
        }
    }

    private static ListTag writeStringList(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            CompoundTag item = new CompoundTag();
            item.putString("value", value);
            list.add(item);
        }
        return list;
    }

    private static void readStringList(ListTag list, List<String> target) {
        for (int i = 0; i < list.size(); i++) {
            String value = list.getCompoundOrEmpty(i).getStringOr("value", "");
            if (!value.isBlank() && !target.contains(value)) {
                target.add(value);
            }
        }
    }

    private static void migrateLegacyCourseData(CompoundTag root) {
        migrateLegacyCourses(root);
        migrateLegacyPlayers(root);
        migrateLegacyCheckpointProgress(root);
        migrateLegacyActiveRuns(root);
    }

    private static void migrateLegacyCourses(CompoundTag root) {
        CourseConfig course = getOrCreateCourse(DEFAULT_COURSE_ID);

        readDimPosMap(root.getListOrEmpty("customSpawns"), course.customSpawns);
        readDimPosMap(root.getListOrEmpty("checkpoints"), course.checkpoints);

        CompoundTag respawn = root.getCompoundOrEmpty("globalRespawn");
        if (!respawn.isEmpty()) {
            ResourceKey<Level> dim = parseWorldKey(respawn.getStringOr("dim", ""));
            BlockPos pos = posFromNbt(respawn.getCompoundOrEmpty("pos"));
            if (dim != null && pos != null) {
                course.respawnDim = dim;
                course.respawnPos = pos;
            }
        }

        if (root.contains("fallY")) {
            course.fallYThreshold = root.getIntOr("fallY", Integer.MIN_VALUE);
        }
    }

    private static void migrateLegacyPlayers(CompoundTag root) {
        addLegacyUuidSet(root.getListOrEmpty("completed"), COMPLETED_COURSES);
        addLegacyUuidSet(root.getListOrEmpty("discovered"), DISCOVERED_COURSES);
    }

    private static void migrateLegacyCheckpointProgress(CompoundTag root) {
        ListTag list = root.getListOrEmpty("checkpointProgress");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            ResourceKey<Level> dim = parseWorldKey(item.getStringOr("dim", ""));
            int index = item.getIntOr("index", -1);
            if (playerId != null && dim != null && index >= 0) {
                CHECKPOINT_PROGRESS.putIfAbsent(playerId, new ActiveCheckpoint(DEFAULT_COURSE_ID, index, dim));
            }
        }
    }

    private static void migrateLegacyActiveRuns(CompoundTag root) {
        Map<UUID, Long> timerStart = new ConcurrentHashMap<>();
        readLegacyUuidLongMap(root.getListOrEmpty("timerStart"), timerStart);

        ListTag list = root.getListOrEmpty("activeChests");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            ResourceKey<Level> dim = parseWorldKey(item.getStringOr("dim", ""));
            BlockPos pos = posFromNbt(item.getCompoundOrEmpty("pos"));
            boolean repeatRun = item.getBooleanOr("timeTrial", false);
            long startTick = timerStart.getOrDefault(playerId, 0L);
            if (playerId != null && dim != null && pos != null) {
                ACTIVE_RUNS.putIfAbsent(playerId, new ActiveRun(DEFAULT_COURSE_ID, pos, dim, startTick, repeatRun));
            }
        }
    }

    private static void addLegacyUuidSet(ListTag list, Map<UUID, Set<String>> target) {
        for (int i = 0; i < list.size(); i++) {
            UUID playerId = parseUuid(list.getCompoundOrEmpty(i).getStringOr("id", ""));
            if (playerId != null) {
                addCourseFlag(target, playerId, DEFAULT_COURSE_ID);
            }
        }
    }

    private static void readLegacyUuidLongMap(ListTag list, Map<UUID, Long> target) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getStringOr("id", ""));
            if (playerId != null) {
                target.put(playerId, item.getLongOr("v", 0L));
            }
        }
    }

    private static void clearTransientState() {
        LAST_DIALOG_TICK.clear();
        LAST_ELYTRA_WARN.clear();
        AREA_WAND_COURSE.clear();
        CHECKPOINT_WAND_COURSE.clear();
        PENDING_OFFERS.clear();
    }

    private static void sendPlayerMessage(ServerPlayer player, Component component, boolean ignored) {
        player.sendSystemMessage(component);
    }

    private static CompoundTag posToNbt(BlockPos pos) {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("x", pos.getX());
        nbt.putInt("y", pos.getY());
        nbt.putInt("z", pos.getZ());
        return nbt;
    }

    private static BlockPos posFromNbt(CompoundTag nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return null;
        }
        return new BlockPos(nbt.getIntOr("x", 0), nbt.getIntOr("y", 0), nbt.getIntOr("z", 0));
    }

    private static ResourceKey<Level> parseWorldKey(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return ResourceKey.create(Registries.DIMENSION, Identifier.parse(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private record TryPlaceResult(boolean success, BlockPos placedPos, Component failureMessage) {
        private static TryPlaceResult success(BlockPos pos) {
            return new TryPlaceResult(true, pos, null);
        }

        private static TryPlaceResult fail(Component message) {
            return new TryPlaceResult(false, null, message);
        }
    }

    private record PendingOffer(String courseId, OfferType type) {}

    private record ActiveRun(String courseId, BlockPos pos, ResourceKey<Level> dimension, long startTick, boolean repeatRun) {}

    private record ActiveCheckpoint(String courseId, int index, ResourceKey<Level> dimension) {}

    private record RewardTier(int maxSeconds, ItemReward reward) {}

    private record CommandRewardTier(int maxSeconds, List<String> commands) {}

    private record ItemReward(Item item, int count) {}

    private record ResolvedItemReward(ItemReward reward, String claimKey, boolean fallback) {}

    private record ResolvedCommandReward(List<String> commands, String claimKey, boolean fallback) {}

    private record RewardDailyState(String date, int count) {}

    private static final class CourseConfig {
        private final String id;
        private final Map<ResourceKey<Level>, List<BlockPos>> customSpawns = new ConcurrentHashMap<>();
        private final Map<ResourceKey<Level>, List<BlockPos>> checkpoints = new ConcurrentHashMap<>();
        private final List<RewardTier> rewardTiers = Collections.synchronizedList(new ArrayList<>());
        private final List<CommandRewardTier> commandRewardTiers = Collections.synchronizedList(new ArrayList<>());
        private final List<String> fallbackCommands = Collections.synchronizedList(new ArrayList<>());
        private BlockPos respawnPos;
        private ResourceKey<Level> respawnDim;
        private int fallYThreshold = Integer.MIN_VALUE;
        private ItemReward fallbackReward;
        private boolean rewardRequireNewBest;
        private boolean rewardOncePerTier;
        private int rewardDailyLimit;

        private CourseConfig(String id) {
            this.id = id;
        }
    }
}
