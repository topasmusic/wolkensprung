package de.wolkensprung.quest;

import de.wolkensprung.data.WolkensprungState;
import de.wolkensprung.entity.QuestGiverEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

    private enum RewardBlockReason {
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
            RegistryKey<World> respawnDim,
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

    public static ActionResult onVillagerInteract(ServerWorld world, ServerPlayerEntity player, Hand hand, Entity entity) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (!isQuestGiver(entity)) {
            return ActionResult.PASS;
        }

        String courseId = getQuestGiverCourseId(entity);
        markDiscovered(world, player.getUuid(), courseId);
        return handleQuestDialog(world, player, courseId);
    }

    public static void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ActiveRun activeRun = ACTIVE_RUNS.get(player.getUuid());
            if (activeRun == null) {
                continue;
            }

            if (player.isGliding()) {
                player.stopGliding();
            }

            ServerWorld playerWorld = (ServerWorld) player.getEntityWorld();
            updateCheckpointProgress(playerWorld, player, activeRun);

            ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
            if (chest.isOf(Items.ELYTRA)) {
                ItemStack removed = chest.copy();
                player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
                if (!player.getInventory().insertStack(removed)) {
                    player.dropItem(removed, false);
                }
                warnElytraBlocked(player, playerWorld);
            }

            CourseConfig course = getCourse(activeRun.courseId());
            if (course == null || course.respawnPos == null || course.respawnDim == null || course.fallYThreshold == Integer.MIN_VALUE) {
                continue;
            }
            if (!playerWorld.getRegistryKey().equals(course.respawnDim)) {
                continue;
            }
            if (player.getY() < course.fallYThreshold) {
                ServerWorld targetWorld = server.getWorld(course.respawnDim);
                if (targetWorld != null) {
                    teleportToRespawn(player, targetWorld, activeRun.courseId());
                }
                continue;
            }

            if (player.age % 8 == 0 && playerWorld.getRegistryKey().equals(activeRun.dimension())) {
                emitChestBeam(playerWorld, player, activeRun.pos());
            }
        }
    }

    public static ActionResult onBlockUse(ServerWorld world, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);

        String checkpointCourseId = CHECKPOINT_WAND_COURSE.get(player.getUuid());
        if (checkpointCourseId != null && player.getStackInHand(hand).isOf(Items.STICK)) {
            addCheckpoint(world, player, checkpointCourseId, pos);
            return ActionResult.SUCCESS;
        }

        String areaCourseId = AREA_WAND_COURSE.get(player.getUuid());
        if (areaCourseId != null && player.getStackInHand(hand).isOf(Items.STICK)) {
            boolean added = addCustomSpawnChecked(world, player, areaCourseId, pos);
            if (added) {
                int count = getCustomSpawnCount(world, areaCourseId);
                player.sendMessage(Text.translatable("quest.wolkensprung.spawn_wand_added", count).formatted(Formatting.GRAY), false);
            }
            return ActionResult.SUCCESS;
        }

        if (!state.isOf(Blocks.CHEST)) {
            return ActionResult.PASS;
        }

        ActiveRun tracked = ACTIVE_RUNS.get(player.getUuid());
        if (tracked == null || !tracked.dimension().equals(world.getRegistryKey()) || !tracked.pos().equals(pos)) {
            return ActionResult.PASS;
        }

        finish(world, player, tracked);
        return ActionResult.SUCCESS;
    }

    public static boolean acceptPendingOffer(ServerWorld world, ServerPlayerEntity player) {
        PendingOffer offer = player == null ? null : PENDING_OFFERS.remove(player.getUuid());
        if (offer == null) {
            if (player != null) {
                player.sendMessage(Text.translatable("command.wolkensprung.quest.no_offer").formatted(Formatting.RED), false);
            }
            return false;
        }

        if (offer.type() == OfferType.TIMER) {
            return acceptTimer(world, player, offer.courseId());
        }
        return accept(world, player, offer.courseId());
    }

    public static boolean declinePendingOffer(ServerPlayerEntity player) {
        PendingOffer offer = player == null ? null : PENDING_OFFERS.remove(player.getUuid());
        if (offer == null) {
            if (player != null) {
                player.sendMessage(Text.translatable("command.wolkensprung.quest.no_offer").formatted(Formatting.RED), false);
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

    public static boolean cancel(ServerWorld world, ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        ActiveRun run = ACTIVE_RUNS.remove(playerId);
        if (run != null) {
            ServerWorld targetWorld = world.getServer().getWorld(run.dimension());
            if (targetWorld != null && targetWorld.getBlockState(run.pos()).isOf(Blocks.CHEST)) {
                targetWorld.breakBlock(run.pos(), false, player);
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

    public static void markDiscovered(ServerWorld world, UUID playerId) {
        markDiscovered(world, playerId, DEFAULT_COURSE_ID);
    }

    public static boolean hasDiscovered(UUID playerId) {
        Set<String> courses = DISCOVERED_COURSES.get(playerId);
        return courses != null && !courses.isEmpty();
    }

    public static boolean createCourse(ServerWorld world, String rawCourseId) {
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

    public static boolean hasCustomSpawns(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return false;
        }
        List<BlockPos> list = course.customSpawns.get(world.getRegistryKey());
        return list != null && !list.isEmpty();
    }

    public static void openSpawnListScreen(ServerWorld world, ServerPlayerEntity player, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        List<BlockPos> coords = getCustomSpawns(world, courseId);
        if (coords.isEmpty()) {
            player.sendMessage(Text.translatable("quest.wolkensprung.spawn_list.empty").formatted(Formatting.GRAY), false);
            return;
        }

        String setCommandPrefix = commandPrefix(courseId, "area set ");
        String removeCommandPrefix = commandPrefix(courseId, "area remove ");

        player.sendMessage(divider(), false);
        player.sendMessage(Text.translatable("quest.wolkensprung.spawn_list.header", coords.size()).formatted(Formatting.LIGHT_PURPLE), false);
        for (BlockPos pos : coords) {
            String coordString = pos.getX() + " " + pos.getY() + " " + pos.getZ();
            Text set = Text.literal("[S]").styled(style -> style.withColor(Formatting.YELLOW)
                    .withClickEvent(new ClickEvent.SuggestCommand(setCommandPrefix + coordString)));
            Text delete = Text.literal(" [X]").styled(style -> style.withColor(Formatting.RED)
                    .withClickEvent(new ClickEvent.RunCommand(removeCommandPrefix + coordString)));
            player.sendMessage(Text.empty()
                    .append(set)
                    .append(Text.literal(" " + coordString).formatted(Formatting.GRAY))
                    .append(delete), false);
        }
        player.sendMessage(divider(), false);
    }

    public static boolean addCustomSpawnChecked(ServerWorld world, ServerPlayerEntity player, String rawCourseId, BlockPos pos) {
        String courseId = normalizeCourseId(rawCourseId);
        BlockPos immutablePos = pos.toImmutable();
        List<BlockPos> list = getOrCreatePositionList(getOrCreateCourse(courseId).customSpawns, world.getRegistryKey());
        if (list.contains(immutablePos)) {
            player.sendMessage(Text.empty()
                    .append(Text.translatable("quest.wolkensprung.spawn_exists").formatted(Formatting.RED))
                    .append(formatPos(immutablePos)), false);
            return false;
        }

        Text validation = validateSpawnSpot(world, immutablePos);
        if (validation != null) {
            player.sendMessage(validation, false);
            return false;
        }

        list.add(immutablePos);
        markDirty(world);
        return true;
    }

    public static boolean removeCustomSpawn(ServerWorld world, String rawCourseId, BlockPos pos) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return false;
        }
        List<BlockPos> list = course.customSpawns.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) {
            return false;
        }

        boolean removed = list.remove(pos);
        if (removed) {
            if (list.isEmpty()) {
                course.customSpawns.remove(world.getRegistryKey());
            }
            markDirty(world);
        }
        return removed;
    }

    public static int getCustomSpawnCount(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        List<BlockPos> list = course.customSpawns.get(world.getRegistryKey());
        return list == null ? 0 : list.size();
    }

    public static int clearCustomSpawns(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        List<BlockPos> list = course.customSpawns.remove(world.getRegistryKey());
        markDirty(world);
        return list == null ? 0 : list.size();
    }

    public static List<BlockPos> getCustomSpawns(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return List.of();
        }
        List<BlockPos> list = course.customSpawns.get(world.getRegistryKey());
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public static List<BlockPos> getCheckpoints(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return List.of();
        }
        List<BlockPos> list = course.checkpoints.get(world.getRegistryKey());
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public static boolean addCheckpointManual(ServerWorld world, String rawCourseId, BlockPos pos) {
        String courseId = normalizeCourseId(rawCourseId);
        List<BlockPos> list = getOrCreatePositionList(getOrCreateCourse(courseId).checkpoints, world.getRegistryKey());
        BlockPos immutablePos = pos.toImmutable();
        if (list.contains(immutablePos)) {
            return false;
        }
        list.add(immutablePos);
        markDirty(world);
        return true;
    }

    public static boolean removeCheckpoint(ServerWorld world, String rawCourseId, BlockPos pos) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return false;
        }
        List<BlockPos> list = course.checkpoints.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) {
            return false;
        }

        boolean removed = list.remove(pos);
        if (removed) {
            if (list.isEmpty()) {
                course.checkpoints.remove(world.getRegistryKey());
            }
            markDirty(world);
        }
        return removed;
    }

    public static int clearCheckpoints(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        List<BlockPos> list = course.checkpoints.remove(world.getRegistryKey());
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

    public static void setRespawn(ServerWorld world, String rawCourseId, BlockPos pos) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.respawnDim = world.getRegistryKey();
        course.respawnPos = pos.toImmutable();
        markDirty(world);
    }

    public static void clearRespawn(ServerWorld world, String rawCourseId) {
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

    public static RegistryKey<World> getRespawnDim(String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        return course == null ? null : course.respawnDim;
    }

    public static void setFallYThreshold(ServerWorld world, String rawCourseId, int y) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.fallYThreshold = y;
        markDirty(world);
    }

    public static int getFallYThreshold(String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        return course == null ? Integer.MIN_VALUE : course.fallYThreshold;
    }

    public static RewardTierInfo setRewardTier(ServerWorld world, String rawCourseId, int maxSeconds, String itemId, int count) {
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

    public static int clearRewardTiers(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        int removed = course.rewardTiers.size();
        course.rewardTiers.clear();
        markDirty(world);
        return removed;
    }

    public static RewardInfo setFallbackReward(ServerWorld world, String rawCourseId, String itemId, int count) {
        ItemReward reward = parseReward(itemId, count);
        if (reward == null) {
            return null;
        }

        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.fallbackReward = reward;
        markDirty(world);
        return new RewardInfo(itemId(reward.item()), reward.count());
    }

    public static boolean clearFallbackReward(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null || course.fallbackReward == null) {
            return false;
        }
        course.fallbackReward = null;
        markDirty(world);
        return true;
    }

    public static CommandRewardTierInfo addCommandRewardTier(ServerWorld world, String rawCourseId, int maxSeconds, String rawCommand) {
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

    public static int clearCommandRewardTiers(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        int removed = course.commandRewardTiers.size();
        course.commandRewardTiers.clear();
        markDirty(world);
        return removed;
    }

    public static boolean addFallbackCommand(ServerWorld world, String rawCourseId, String rawCommand) {
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

    public static int clearFallbackCommands(ServerWorld world, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return 0;
        }
        int removed = course.fallbackCommands.size();
        course.fallbackCommands.clear();
        markDirty(world);
        return removed;
    }

    public static void setRewardRequireNewBest(ServerWorld world, String rawCourseId, boolean value) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardRequireNewBest = value;
        markDirty(world);
    }

    public static void setRewardOncePerTier(ServerWorld world, String rawCourseId, boolean value) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardOncePerTier = value;
        markDirty(world);
    }

    public static void setRewardDailyLimit(ServerWorld world, String rawCourseId, int value) {
        CourseConfig course = getOrCreateCourse(rawCourseId);
        course.rewardDailyLimit = Math.max(0, value);
        markDirty(world);
    }

    public static Text activeQuestLine(UUID playerId) {
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
        if (entity instanceof net.minecraft.entity.decoration.ArmorStandEntity statue) {
            return isQuestStatue(statue);
        }
        return false;
    }

    public static NbtCompound writeToNbt() {
        NbtCompound root = new NbtCompound();
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

    public static void readFromNbt(NbtCompound root) {
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

    private static ActionResult handleQuestDialog(ServerWorld world, ServerPlayerEntity player, String courseId) {
        UUID playerId = player.getUuid();
        ActiveRun activeRun = ACTIVE_RUNS.get(playerId);
        if (activeRun != null) {
            player.sendMessage(activeChestHint(activeRun), false);
            return ActionResult.SUCCESS;
        }

        if (!hasCustomSpawns(world, courseId)) {
            if (!throttleDialog(world, playerId, 10)) {
                player.sendMessage(Text.empty()
                        .append(errorTag())
                        .append(Text.translatable("quest.wolkensprung.no_spawns").formatted(Formatting.RED)), false);
                showSpawnSetup(world, player, courseId);
            }
            return ActionResult.SUCCESS;
        }

        if (hasCompleted(playerId, courseId)) {
            if (!throttleDialog(world, playerId, 10)) {
                showReturnDialog(player, courseId);
            }
            return ActionResult.SUCCESS;
        }

        if (!throttleDialog(world, playerId, 10)) {
            showOffer(player, courseId);
        }
        return ActionResult.SUCCESS;
    }

    private static boolean accept(ServerWorld world, ServerPlayerEntity player, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        PENDING_OFFERS.remove(player.getUuid());
        markDiscovered(world, player.getUuid(), courseId);
        if (hasCompleted(player.getUuid(), courseId)) {
            player.sendMessage(Text.translatable("quest.wolkensprung.already_completed", questDisplayName(courseId)).formatted(Formatting.GRAY), false);
            return false;
        }
        return startRun(world, player, courseId, false);
    }

    private static boolean acceptTimer(ServerWorld world, ServerPlayerEntity player, String rawCourseId) {
        String courseId = normalizeCourseId(rawCourseId);
        PENDING_OFFERS.remove(player.getUuid());
        if (!hasCompleted(player.getUuid(), courseId)) {
            player.sendMessage(Text.translatable("quest.wolkensprung.timer_locked").formatted(Formatting.RED), false);
            return false;
        }
        return startRun(world, player, courseId, true);
    }

    private static boolean startRun(ServerWorld world, ServerPlayerEntity player, String courseId, boolean repeatRun) {
        UUID playerId = player.getUuid();
        clearPlayerCheckpoints(world, playerId);
        if (ACTIVE_RUNS.containsKey(playerId)) {
            player.sendMessage(activeChestHint(ACTIVE_RUNS.get(playerId)), false);
            return false;
        }

        BlockPos placed = placeChest(world, player, courseId);
        if (placed == null) {
            if (!hasCustomSpawns(world, courseId)) {
                player.sendMessage(Text.translatable("quest.wolkensprung.no_spawns").formatted(Formatting.RED), false);
            } else {
                player.sendMessage(Text.translatable("quest.wolkensprung.no_chest_space").formatted(Formatting.RED), false);
            }
            return false;
        }

        ACTIVE_RUNS.put(playerId, new ActiveRun(courseId, placed, world.getRegistryKey(), world.getTime(), repeatRun));
        markDirty(world);

        if (repeatRun) {
            player.sendMessage(Text.empty()
                    .append(Text.translatable("quest.wolkensprung.timer_started").formatted(Formatting.GRAY))
                    .append(kofferToken()), false);
            return true;
        }

        Text divider = divider();
        player.sendMessage(Text.empty()
                .append(Text.literal("\n"))
                .append(divider.copy()).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.accept.line1").formatted(Formatting.GRAY)).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.accept.line2").formatted(Formatting.GRAY)).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.accept.line3").formatted(Formatting.GRAY)).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.accept.line4").formatted(Formatting.GRAY)).append(Text.literal("\n\n"))
                .append(kofferHintLine()).append(Text.literal("\n"))
                .append(divider.copy())
                .append(Text.literal("\n\n")), false);
        return true;
    }

    private static void declineTimer(ServerPlayerEntity player) {
        player.sendMessage(Text.translatable("quest.wolkensprung.timer_declined").formatted(Formatting.GRAY), false);
    }

    private static void declineOffer(ServerPlayerEntity player) {
        player.sendMessage(Text.translatable("quest.wolkensprung.offer_declined").formatted(Formatting.GRAY), false);
    }

    private static void markDiscovered(ServerWorld world, UUID playerId, String rawCourseId) {
        addCourseFlag(DISCOVERED_COURSES, playerId, rawCourseId);
        markDirty(world);
    }

    private static void markDirty(ServerWorld world) {
        if (world != null) {
            WolkensprungState.get(world.getServer()).updateFromRuntime();
        }
    }

    private static void finish(ServerWorld world, ServerPlayerEntity player, ActiveRun run) {
        UUID playerId = player.getUuid();
        String courseId = run.courseId();
        boolean firstCompletion = !hasCompleted(playerId, courseId);
        ACTIVE_RUNS.remove(playerId);
        world.breakBlock(run.pos(), false, player);
        clearPlayerCheckpoints(world, playerId);

        long elapsedTicks = Math.max(0L, world.getTime() - run.startTick());
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

        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.9f, 1.0f);
        player.sendMessage(Text.translatable("quest.wolkensprung.time", formatSeconds(elapsedTicks)).formatted(Formatting.GREEN), false);
        if (itemReward != null) {
            player.sendMessage(Text.translatable("quest.wolkensprung.reward.line", rewardText(itemReward.reward())).formatted(Formatting.GREEN), false);
        }
        if (commandReward != null) {
            player.sendMessage(Text.translatable("quest.wolkensprung.reward.command_line").formatted(Formatting.GREEN), false);
        }
        if (rewardBlockReason != null) {
            player.sendMessage(rewardBlockedMessage(rewardBlockReason), false);
        }
        if (newBest && bestTicks != null) {
            player.sendMessage(Text.translatable("quest.wolkensprung.best.new", formatSeconds(bestTicks)).formatted(Formatting.AQUA), false);
        } else if (bestTicks != null) {
            player.sendMessage(Text.translatable("quest.wolkensprung.best.current", formatSeconds(bestTicks)).formatted(Formatting.GRAY), false);
        }
        player.sendMessage(Text.translatable(
                firstCompletion ? "quest.wolkensprung.completed" : "quest.wolkensprung.repeat_completed",
                questDisplayName(courseId)).formatted(Formatting.GREEN), false);
        markDirty(world);
    }

    private static void giveReward(ServerPlayerEntity player, ItemReward reward) {
        int remaining = reward.count();
        int maxStack = Math.max(1, reward.item().getMaxCount());
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStack);
            player.getInventory().offerOrDrop(new ItemStack(reward.item(), stackSize));
            remaining -= stackSize;
        }
    }

    private static void executeRewardCommands(ServerWorld world, ServerPlayerEntity player, String rawCourseId, long elapsedTicks, List<String> commands) {
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
            var source = world.getServer().getCommandSource().withSilent();
            var parsed = world.getServer().getCommandManager().getDispatcher().parse(command, source);
            world.getServer().getCommandManager().execute(parsed, command);
        }
    }

    private static void showOffer(ServerPlayerEntity player, String courseId) {
        PENDING_OFFERS.put(player.getUuid(), new PendingOffer(courseId, OfferType.MAIN));

        Text accept = Text.translatable("quest.wolkensprung.offer.accept").styled(style -> style
                .withColor(Formatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_ACCEPT_COMMAND)));
        Text decline = Text.translatable("quest.wolkensprung.offer.decline").styled(style -> style
                .withColor(Formatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_DECLINE_COMMAND)));

        player.sendMessage(Text.empty()
                .append(Text.literal("\n"))
                .append(divider().copy()).append(Text.literal("\n"))
                .append(questTitle(courseId)).append(Text.literal("\n\n"))
                .append(Text.translatable("quest.wolkensprung.offer.line1").formatted(Formatting.GRAY)).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.offer.line2").formatted(Formatting.GRAY)).append(Text.literal("\n\n"))
                .append(accept).append(Text.literal("\n"))
                .append(decline).append(Text.literal("\n"))
                .append(divider().copy())
                .append(Text.literal("\n\n")), false);
    }

    private static void showSpawnSetup(ServerWorld world, ServerPlayerEntity player, String courseId) {
        BlockPos pos = player.getBlockPos().down();
        String setCommand = commandPrefix(courseId, "area set ") + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String setHereCommand = commandPrefix(courseId, "area sethere");

        player.sendMessage(Text.empty()
                .append(Text.literal("\n"))
                .append(divider().copy()).append(Text.literal("\n"))
                .append(questTitle(courseId)).append(Text.literal("\n\n"))
                .append(Text.translatable("quest.wolkensprung.setup.info").formatted(Formatting.GRAY)).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.setup.prompt").formatted(Formatting.GRAY)).append(Text.literal("\n"))
                .append(Text.literal("    " + setCommand).styled(style -> style
                        .withColor(Formatting.GRAY)
                        .withClickEvent(new ClickEvent.SuggestCommand(setCommand)))).append(Text.literal("\n"))
                .append(Text.translatable("quest.wolkensprung.setup.current").styled(style -> style
                        .withColor(Formatting.GRAY)
                        .withClickEvent(new ClickEvent.RunCommand(setHereCommand)))).append(Text.literal("\n"))
                .append(divider().copy())
                .append(Text.literal("\n\n")), false);
    }

    private static void showReturnDialog(ServerPlayerEntity player, String courseId) {
        PENDING_OFFERS.put(player.getUuid(), new PendingOffer(courseId, OfferType.TIMER));

        Text accept = Text.translatable("quest.wolkensprung.return.accept").styled(style -> style
                .withColor(Formatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_ACCEPT_COMMAND)));
        Text decline = Text.translatable("quest.wolkensprung.return.decline").styled(style -> style
                .withColor(Formatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand(QUEST_DECLINE_COMMAND)));

        player.sendMessage(Text.empty()
                .append(Text.literal("\n"))
                .append(divider().copy()).append(Text.literal("\n"))
                .append(questTitle(courseId)).append(Text.literal("\n\n"))
                .append(Text.translatable("quest.wolkensprung.return.prompt").formatted(Formatting.GRAY)).append(Text.literal("\n\n"))
                .append(accept).append(Text.literal("\n"))
                .append(decline).append(Text.literal("\n"))
                .append(divider().copy())
                .append(Text.literal("\n\n")), false);
    }

    private static void teleportToRespawn(ServerPlayerEntity player, ServerWorld targetWorld, String courseId) {
        if (!hasActive(player.getUuid())) {
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
        player.setVelocity(0.0, 0.0, 0.0);
        player.teleport(
                targetWorld,
                pos.getX() + 0.5,
                pos.getY() + 0.1,
                pos.getZ() + 0.5,
                EnumSet.noneOf(PositionFlag.class),
                player.getYaw(),
                player.getPitch(),
                false
        );
        targetWorld.playSound(
                null,
                pos,
                usedCheckpoint ? SoundEvents.ENTITY_ENDER_PEARL_THROW : SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.PLAYERS,
                usedCheckpoint ? 1.2f : 0.9f,
                1.0f
        );
    }

    private static boolean throttleDialog(ServerWorld world, UUID playerId, int minGapTicks) {
        long now = world.getTime();
        long last = LAST_DIALOG_TICK.getOrDefault(playerId, -minGapTicks - 1L);
        if ((now - last) < minGapTicks) {
            return true;
        }
        LAST_DIALOG_TICK.put(playerId, now);
        return false;
    }

    private static void warnElytraBlocked(ServerPlayerEntity player, ServerWorld world) {
        UUID playerId = player.getUuid();
        long now = world.getTime();
        long last = LAST_ELYTRA_WARN.getOrDefault(playerId, -200L);
        if ((now - last) < 40L) {
            return;
        }
        LAST_ELYTRA_WARN.put(playerId, now);
        player.sendMessage(Text.translatable("quest.wolkensprung.elytra_blocked").formatted(Formatting.GRAY), false);
    }

    private static boolean isQuestStatue(net.minecraft.entity.decoration.ArmorStandEntity statue) {
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

    private static Text activeChestHint(ActiveRun run) {
        return Text.empty()
                .append(Text.translatable("quest.wolkensprung.chest_hint.prefix").formatted(Formatting.GRAY))
                .append(kofferToken())
                .append(Text.translatable("quest.wolkensprung.chest_hint.suffix").formatted(Formatting.GRAY));
    }

    private static Text divider() {
        return Text.literal("------------------------------").formatted(Formatting.GRAY);
    }

    private static Text questName() {
        return Text.translatable("quest.wolkensprung.name");
    }

    private static Text questTag() {
        return Text.translatable("ui.wolkensprung.quest_tag").formatted(Formatting.GRAY);
    }

    private static Text errorTag() {
        return Text.translatable("ui.wolkensprung.error_tag").formatted(Formatting.GRAY);
    }

    private static MutableText questTitle(String courseId) {
        MutableText title = Text.empty()
                .append(questTag())
                .append(questName().copy().formatted(Formatting.LIGHT_PURPLE));
        if (!DEFAULT_COURSE_ID.equals(courseId)) {
            title.append(Text.literal(" [" + courseId + "]").formatted(Formatting.YELLOW));
        }
        return title;
    }

    private static MutableText questDisplayName(String courseId) {
        MutableText display = questName().copy();
        if (!DEFAULT_COURSE_ID.equals(courseId)) {
            display.append(Text.literal(" [" + courseId + "]").formatted(Formatting.YELLOW));
        }
        return display;
    }

    private static Text formatPos(BlockPos pos) {
        return Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).formatted(Formatting.YELLOW);
    }

    private static Text kofferToken() {
        return Text.translatable("quest.wolkensprung.token").styled(style -> style
                .withColor(Formatting.GREEN)
                .withHoverEvent(new HoverEvent.ShowText(Text.translatable("tooltip.wolkensprung.token").formatted(Formatting.GRAY))));
    }

    private static Text kofferHintLine() {
        return Text.empty()
                .append(Text.translatable("quest.wolkensprung.active.prefix").formatted(Formatting.GRAY))
                .append(kofferToken())
                .append(Text.translatable("quest.wolkensprung.active.suffix").formatted(Formatting.GRAY));
    }

    private static void addCheckpoint(ServerWorld world, ServerPlayerEntity player, String rawCourseId, BlockPos pos) {
        String courseId = normalizeCourseId(rawCourseId);
        List<BlockPos> list = getOrCreatePositionList(getOrCreateCourse(courseId).checkpoints, world.getRegistryKey());
        BlockPos immutablePos = pos.toImmutable();
        if (list.contains(immutablePos)) {
            player.sendMessage(Text.translatable("quest.wolkensprung.checkpoint.exists").formatted(Formatting.RED), false);
            return;
        }
        list.add(immutablePos);
        markDirty(world);
        player.sendMessage(Text.translatable("quest.wolkensprung.checkpoint.set_total", list.size()).formatted(Formatting.GRAY), false);
    }

    private static void updateCheckpointProgress(ServerWorld world, ServerPlayerEntity player, ActiveRun run) {
        CourseConfig course = getCourse(run.courseId());
        if (course == null) {
            return;
        }

        List<BlockPos> list = course.checkpoints.get(world.getRegistryKey());
        if (list == null || list.isEmpty() || !player.isOnGround()) {
            return;
        }

        BlockPos playerPos = player.getBlockPos().down();
        ActiveCheckpoint progress = CHECKPOINT_PROGRESS.get(player.getUuid());
        int currentIndex = -1;
        if (progress != null && run.courseId().equals(progress.courseId()) && world.getRegistryKey().equals(progress.dimension())) {
            currentIndex = progress.index();
        }

        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).equals(playerPos)) {
                continue;
            }
            if (i > currentIndex) {
                CHECKPOINT_PROGRESS.put(player.getUuid(), new ActiveCheckpoint(run.courseId(), i, world.getRegistryKey()));
                markDirty(world);
                player.sendMessage(Text.translatable("quest.wolkensprung.checkpoint.reached").formatted(Formatting.GRAY), false);
            }
            return;
        }
    }

    private static BlockPos getCheckpointRespawn(ServerPlayerEntity player, ServerWorld world, String courseId) {
        ActiveCheckpoint checkpoint = CHECKPOINT_PROGRESS.get(player.getUuid());
        if (checkpoint == null) {
            return null;
        }
        if (!normalizeCourseId(courseId).equals(checkpoint.courseId()) || !world.getRegistryKey().equals(checkpoint.dimension())) {
            return null;
        }

        CourseConfig course = getCourse(courseId);
        if (course == null) {
            return null;
        }
        List<BlockPos> list = course.checkpoints.get(world.getRegistryKey());
        if (list == null || checkpoint.index() < 0 || checkpoint.index() >= list.size()) {
            return null;
        }
        return list.get(checkpoint.index()).up();
    }

    private static void clearPlayerCheckpoints(ServerWorld world, UUID playerId) {
        CHECKPOINT_PROGRESS.remove(playerId);
        markDirty(world);
    }

    private static BlockPos placeChest(ServerWorld world, ServerPlayerEntity player, String courseId) {
        return tryPlaceFromCustomList(world, player, courseId);
    }

    private static BlockPos tryPlaceFromCustomList(ServerWorld world, ServerPlayerEntity player, String rawCourseId) {
        CourseConfig course = getCourse(rawCourseId);
        if (course == null) {
            return null;
        }
        List<BlockPos> list = course.customSpawns.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) {
            return null;
        }

        List<BlockPos> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);
        List<BlockPos> toRemove = new ArrayList<>();
        Text lastFailure = null;

        for (BlockPos pos : shuffled) {
            Text validation = validateSpawnSpot(world, pos);
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
                course.customSpawns.remove(world.getRegistryKey());
            }
            markDirty(world);
        }
        if (lastFailure != null) {
            player.sendMessage(lastFailure, false);
        }
        return null;
    }

    private static TryPlaceResult tryPlaceChestOnTop(ServerWorld world, ServerPlayerEntity player, BlockPos basePos) {
        BlockState baseState = world.getBlockState(basePos);
        if (baseState.isIn(BlockTags.LOGS) || !hasFlatTop(world, basePos, baseState) || !world.isAir(basePos.up())) {
            return TryPlaceResult.fail(Text.empty()
                    .append(Text.translatable("quest.wolkensprung.spawn.invalid_generic").formatted(Formatting.RED))
                    .append(formatPos(basePos)));
        }

        BlockPos chestPos = basePos.up();
        BlockState chestState = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, player.getHorizontalFacing().getOpposite());
        world.setBlockState(chestPos, chestState);
        return TryPlaceResult.success(chestPos);
    }

    private static void emitChestBeam(ServerWorld world, ServerPlayerEntity player, BlockPos chestPos) {
        double x = chestPos.getX() + 0.5;
        double z = chestPos.getZ() + 0.5;
        for (int i = 0; i < 10; i++) {
            double y = chestPos.getY() + 0.6 + (i * 0.85);
            world.spawnParticles(player, ParticleTypes.END_ROD, true, false, x, y, z, 2, 0.08, 0.05, 0.08, 0.0);
        }
    }

    private static Text validateSpawnSpot(ServerWorld world, BlockPos basePos) {
        BlockState baseState = world.getBlockState(basePos);
        if (baseState.isIn(BlockTags.LOGS)) {
            return Text.empty()
                    .append(Text.translatable("quest.wolkensprung.spawn.invalid_log").formatted(Formatting.RED))
                    .append(formatPos(basePos));
        }
        if (!hasFlatTop(world, basePos, baseState)) {
            return Text.empty()
                    .append(Text.translatable("quest.wolkensprung.spawn.invalid_flat").formatted(Formatting.RED))
                    .append(formatPos(basePos));
        }
        if (!world.isAir(basePos.up())) {
            return Text.empty()
                    .append(Text.translatable("quest.wolkensprung.spawn.invalid_blocked").formatted(Formatting.RED))
                    .append(formatPos(basePos));
        }
        return null;
    }

    private static boolean hasFlatTop(ServerWorld world, BlockPos pos, BlockState state) {
        return Block.isShapeFullCube(state.getCollisionShape(world, pos));
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

    private static MutableText rewardBlockedMessage(RewardBlockReason reason) {
        return switch (reason) {
            case NEW_BEST_ONLY -> Text.translatable("quest.wolkensprung.reward.blocked.new_best_only").formatted(Formatting.GRAY);
            case DAILY_LIMIT -> Text.translatable("quest.wolkensprung.reward.blocked.daily_limit").formatted(Formatting.GRAY);
            case TIER_ALREADY_CLAIMED -> Text.translatable("quest.wolkensprung.reward.blocked.tier_claimed").formatted(Formatting.GRAY);
        };
    }

    private static ItemReward parseReward(String rawItemId, int count) {
        if (rawItemId == null || rawItemId.isBlank() || count <= 0) {
            return null;
        }

        Identifier itemId;
        try {
            itemId = Identifier.of(rawItemId);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        if (!Registries.ITEM.containsId(itemId)) {
            return null;
        }

        Item item = Registries.ITEM.get(itemId);
        if (item == Items.AIR) {
            return null;
        }
        return new ItemReward(item, count);
    }

    private static String itemId(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    private static Text rewardText(ItemReward reward) {
        return Text.empty()
                .append(Text.literal(Integer.toString(reward.count())).formatted(Formatting.YELLOW))
                .append(Text.literal("x ").formatted(Formatting.GRAY))
                .append(reward.item().getName().copy().formatted(Formatting.AQUA));
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

    private static String applyRewardCommandPlaceholders(String command, ServerPlayerEntity player, String courseId, long elapsedTicks) {
        return command
                .replace("%player%", player.getNameForScoreboard())
                .replace("%uuid%", player.getUuidAsString())
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

    private static List<BlockPos> getOrCreatePositionList(Map<RegistryKey<World>, List<BlockPos>> map, RegistryKey<World> dimension) {
        return map.computeIfAbsent(dimension, ignored -> Collections.synchronizedList(new ArrayList<>()));
    }

    private static int countPositions(Map<RegistryKey<World>, List<BlockPos>> map) {
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

    private static NbtList writeCourses() {
        NbtList list = new NbtList();
        for (CourseConfig course : COURSES.values()) {
            NbtCompound item = new NbtCompound();
            item.putString("id", course.id);
            if (course.respawnPos != null && course.respawnDim != null) {
                NbtCompound respawn = new NbtCompound();
                respawn.putString("dim", course.respawnDim.getValue().toString());
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
            if (course.rewardDailyLimit > 0) {
                item.putInt("rewardDailyLimit", course.rewardDailyLimit);
            }
            list.add(item);
        }
        return list;
    }

    private static void readCourses(NbtList list) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            String courseId = normalizeCourseId(item.getString("id", DEFAULT_COURSE_ID));
            CourseConfig course = getOrCreateCourse(courseId);

            NbtCompound respawn = item.getCompoundOrEmpty("respawn");
            if (!respawn.isEmpty()) {
                RegistryKey<World> dim = parseWorldKey(respawn.getString("dim", ""));
                BlockPos pos = posFromNbt(respawn.getCompoundOrEmpty("pos"));
                if (dim != null && pos != null) {
                    course.respawnDim = dim;
                    course.respawnPos = pos;
                }
            }

            if (item.contains("fallY")) {
                course.fallYThreshold = item.getInt("fallY", Integer.MIN_VALUE);
            }

            readDimPosMap(item.getListOrEmpty("customSpawns"), course.customSpawns);
            readDimPosMap(item.getListOrEmpty("checkpoints"), course.checkpoints);
            readRewardTiers(item.getListOrEmpty("rewardTiers"), course.rewardTiers);
            readCommandRewardTiers(item.getListOrEmpty("commandRewardTiers"), course.commandRewardTiers);
            course.fallbackReward = readReward(item.getCompoundOrEmpty("fallbackReward"));
            readStringList(item.getListOrEmpty("fallbackCommands"), course.fallbackCommands);
            course.rewardRequireNewBest = item.getBoolean("rewardRequireNewBest", false);
            course.rewardOncePerTier = item.getBoolean("rewardOncePerTier", false);
            course.rewardDailyLimit = Math.max(0, item.getInt("rewardDailyLimit", 0));
            sortRewardTiers(course);
            sortCommandRewardTiers(course);
        }
    }

    private static NbtList writeActiveRuns() {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, ActiveRun> entry : ACTIVE_RUNS.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveRun run = entry.getValue();
            if (playerId == null || run == null || run.dimension() == null || run.pos() == null) {
                continue;
            }
            NbtCompound item = new NbtCompound();
            item.putString("id", playerId.toString());
            item.putString("courseId", run.courseId());
            item.putString("dim", run.dimension().getValue().toString());
            item.put("pos", posToNbt(run.pos()));
            item.putLong("startTick", run.startTick());
            item.putBoolean("repeatRun", run.repeatRun());
            list.add(item);
        }
        return list;
    }

    private static void readActiveRuns(NbtCompound root) {
        NbtList activeRuns = root.getListOrEmpty("activeRuns");
        for (int i = 0; i < activeRuns.size(); i++) {
            NbtCompound item = activeRuns.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            String courseId = normalizeCourseId(item.getString("courseId", DEFAULT_COURSE_ID));
            RegistryKey<World> dim = parseWorldKey(item.getString("dim", ""));
            BlockPos pos = posFromNbt(item.getCompoundOrEmpty("pos"));
            long startTick = item.getLong("startTick", 0L);
            boolean repeatRun = item.getBoolean("repeatRun", false);
            if (playerId != null && dim != null && pos != null) {
                ACTIVE_RUNS.put(playerId, new ActiveRun(courseId, pos, dim, startTick, repeatRun));
            }
        }
    }

    private static NbtList writeUuidCourseSetMap(Map<UUID, Set<String>> map) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, Set<String>> entry : map.entrySet()) {
            UUID playerId = entry.getKey();
            Set<String> courses = entry.getValue();
            if (playerId == null || courses == null || courses.isEmpty()) {
                continue;
            }
            NbtCompound item = new NbtCompound();
            item.putString("id", playerId.toString());
            NbtList coursesList = new NbtList();
            for (String courseId : new HashSet<>(courses)) {
                NbtCompound course = new NbtCompound();
                course.putString("courseId", courseId);
                coursesList.add(course);
            }
            item.put("courses", coursesList);
            list.add(item);
        }
        return list;
    }

    private static void readUuidCourseSetMap(NbtList list, Map<UUID, Set<String>> target) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            if (playerId == null) {
                continue;
            }
            Set<String> courses = target.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
            NbtList coursesList = item.getListOrEmpty("courses");
            for (int c = 0; c < coursesList.size(); c++) {
                String courseId = normalizeCourseId(coursesList.getCompoundOrEmpty(c).getString("courseId", DEFAULT_COURSE_ID));
                courses.add(courseId);
            }
        }
    }

    private static NbtList writePersonalBestMap() {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, Map<String, Long>> entry : PERSONAL_BEST_TICKS.entrySet()) {
            UUID playerId = entry.getKey();
            Map<String, Long> bests = entry.getValue();
            if (playerId == null || bests == null || bests.isEmpty()) {
                continue;
            }

            NbtCompound item = new NbtCompound();
            item.putString("id", playerId.toString());
            NbtList bestList = new NbtList();
            for (Map.Entry<String, Long> bestEntry : bests.entrySet()) {
                NbtCompound best = new NbtCompound();
                best.putString("courseId", normalizeCourseId(bestEntry.getKey()));
                best.putLong("ticks", bestEntry.getValue());
                bestList.add(best);
            }
            item.put("best", bestList);
            list.add(item);
        }
        return list;
    }

    private static void readPersonalBestMap(NbtList list) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            if (playerId == null) {
                continue;
            }

            Map<String, Long> bests = PERSONAL_BEST_TICKS.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            NbtList bestList = item.getListOrEmpty("best");
            for (int b = 0; b < bestList.size(); b++) {
                NbtCompound best = bestList.getCompoundOrEmpty(b);
                bests.put(normalizeCourseId(best.getString("courseId", DEFAULT_COURSE_ID)), best.getLong("ticks", 0L));
            }
        }
    }

    private static NbtList writeDailyRewardState() {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, Map<String, RewardDailyState>> entry : DAILY_REWARD_STATE.entrySet()) {
            UUID playerId = entry.getKey();
            Map<String, RewardDailyState> byCourse = entry.getValue();
            if (playerId == null || byCourse == null || byCourse.isEmpty()) {
                continue;
            }

            NbtCompound item = new NbtCompound();
            item.putString("id", playerId.toString());
            NbtList courseList = new NbtList();
            for (Map.Entry<String, RewardDailyState> courseEntry : byCourse.entrySet()) {
                RewardDailyState state = courseEntry.getValue();
                if (state == null || state.date() == null || state.date().isBlank()) {
                    continue;
                }
                NbtCompound courseItem = new NbtCompound();
                courseItem.putString("courseId", normalizeCourseId(courseEntry.getKey()));
                courseItem.putString("date", state.date());
                courseItem.putInt("count", state.count());
                courseList.add(courseItem);
            }
            if (!courseList.isEmpty()) {
                item.put("courses", courseList);
                list.add(item);
            }
        }
        return list;
    }

    private static void readDailyRewardState(NbtList list) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            if (playerId == null) {
                continue;
            }

            Map<String, RewardDailyState> byCourse = DAILY_REWARD_STATE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            NbtList courseList = item.getListOrEmpty("courses");
            for (int c = 0; c < courseList.size(); c++) {
                NbtCompound courseItem = courseList.getCompoundOrEmpty(c);
                String courseId = normalizeCourseId(courseItem.getString("courseId", DEFAULT_COURSE_ID));
                String date = courseItem.getString("date", "");
                int count = courseItem.getInt("count", 0);
                if (!date.isBlank() && count > 0) {
                    byCourse.put(courseId, new RewardDailyState(date, count));
                }
            }
        }
    }

    private static NbtList writeClaimedRewardKeys() {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, Map<String, Set<String>>> entry : CLAIMED_REWARD_KEYS.entrySet()) {
            UUID playerId = entry.getKey();
            Map<String, Set<String>> byCourse = entry.getValue();
            if (playerId == null || byCourse == null || byCourse.isEmpty()) {
                continue;
            }

            NbtCompound item = new NbtCompound();
            item.putString("id", playerId.toString());
            NbtList courseList = new NbtList();
            for (Map.Entry<String, Set<String>> courseEntry : byCourse.entrySet()) {
                Set<String> keys = courseEntry.getValue();
                if (keys == null || keys.isEmpty()) {
                    continue;
                }
                NbtCompound courseItem = new NbtCompound();
                courseItem.putString("courseId", normalizeCourseId(courseEntry.getKey()));
                courseItem.put("keys", writeStringList(keys));
                courseList.add(courseItem);
            }
            if (!courseList.isEmpty()) {
                item.put("courses", courseList);
                list.add(item);
            }
        }
        return list;
    }

    private static void readClaimedRewardKeys(NbtList list) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            if (playerId == null) {
                continue;
            }

            Map<String, Set<String>> byCourse = CLAIMED_REWARD_KEYS.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            NbtList courseList = item.getListOrEmpty("courses");
            for (int c = 0; c < courseList.size(); c++) {
                NbtCompound courseItem = courseList.getCompoundOrEmpty(c);
                String courseId = normalizeCourseId(courseItem.getString("courseId", DEFAULT_COURSE_ID));
                Set<String> keys = ConcurrentHashMap.newKeySet();
                readStringList(courseItem.getListOrEmpty("keys"), keys);
                if (!keys.isEmpty()) {
                    byCourse.put(courseId, keys);
                }
            }
        }
    }

    private static NbtList writeCheckpointProgress() {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, ActiveCheckpoint> entry : CHECKPOINT_PROGRESS.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveCheckpoint checkpoint = entry.getValue();
            if (playerId == null || checkpoint == null || checkpoint.dimension() == null || checkpoint.index() < 0) {
                continue;
            }

            NbtCompound item = new NbtCompound();
            item.putString("id", playerId.toString());
            item.putString("courseId", checkpoint.courseId());
            item.putInt("index", checkpoint.index());
            item.putString("dim", checkpoint.dimension().getValue().toString());
            list.add(item);
        }
        return list;
    }

    private static void readCheckpointProgress(NbtList list) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            RegistryKey<World> dim = parseWorldKey(item.getString("dim", ""));
            int index = item.getInt("index", -1);
            String courseId = normalizeCourseId(item.getString("courseId", DEFAULT_COURSE_ID));
            if (playerId != null && dim != null && index >= 0) {
                CHECKPOINT_PROGRESS.put(playerId, new ActiveCheckpoint(courseId, index, dim));
            }
        }
    }

    private static NbtList writeDimPosMap(Map<RegistryKey<World>, List<BlockPos>> map) {
        NbtList list = new NbtList();
        for (Map.Entry<RegistryKey<World>, List<BlockPos>> entry : map.entrySet()) {
            RegistryKey<World> dim = entry.getKey();
            if (dim == null) {
                continue;
            }
            NbtCompound item = new NbtCompound();
            item.putString("dim", dim.getValue().toString());
            NbtList posList = new NbtList();
            for (BlockPos pos : entry.getValue()) {
                posList.add(posToNbt(pos));
            }
            item.put("list", posList);
            list.add(item);
        }
        return list;
    }

    private static void readDimPosMap(NbtList list, Map<RegistryKey<World>, List<BlockPos>> map) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            RegistryKey<World> dim = parseWorldKey(item.getString("dim", ""));
            if (dim == null) {
                continue;
            }

            NbtList posList = item.getListOrEmpty("list");
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

    private static NbtList writeRewardTiers(List<RewardTier> tiers) {
        NbtList list = new NbtList();
        for (RewardTier tier : tiers.stream().sorted(Comparator.comparingInt(RewardTier::maxSeconds)).toList()) {
            NbtCompound item = new NbtCompound();
            item.putInt("maxSeconds", tier.maxSeconds());
            item.put("reward", writeReward(tier.reward()));
            list.add(item);
        }
        return list;
    }

    private static void readRewardTiers(NbtList list, List<RewardTier> target) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            int maxSeconds = item.getInt("maxSeconds", -1);
            ItemReward reward = readReward(item.getCompoundOrEmpty("reward"));
            if (maxSeconds >= 0 && reward != null) {
                target.add(new RewardTier(maxSeconds, reward));
            }
        }
    }

    private static NbtList writeCommandRewardTiers(List<CommandRewardTier> tiers) {
        NbtList list = new NbtList();
        for (CommandRewardTier tier : tiers.stream().sorted(Comparator.comparingInt(CommandRewardTier::maxSeconds)).toList()) {
            if (tier.commands().isEmpty()) {
                continue;
            }
            NbtCompound item = new NbtCompound();
            item.putInt("maxSeconds", tier.maxSeconds());
            item.put("commands", writeStringList(tier.commands()));
            list.add(item);
        }
        return list;
    }

    private static void readCommandRewardTiers(NbtList list, List<CommandRewardTier> target) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            int maxSeconds = item.getInt("maxSeconds", -1);
            List<String> commands = Collections.synchronizedList(new ArrayList<>());
            readStringList(item.getListOrEmpty("commands"), commands);
            if (maxSeconds >= 0 && !commands.isEmpty()) {
                target.add(new CommandRewardTier(maxSeconds, commands));
            }
        }
    }

    private static NbtList writeStringList(Iterable<String> strings) {
        NbtList list = new NbtList();
        for (String value : strings) {
            if (value == null || value.isBlank()) {
                continue;
            }
            NbtCompound item = new NbtCompound();
            item.putString("value", value);
            list.add(item);
        }
        return list;
    }

    private static void readStringList(NbtList list, java.util.Collection<String> target) {
        for (int i = 0; i < list.size(); i++) {
            String value = list.getCompoundOrEmpty(i).getString("value", "");
            if (!value.isBlank()) {
                target.add(value);
            }
        }
    }

    private static NbtCompound writeReward(ItemReward reward) {
        NbtCompound item = new NbtCompound();
        item.putString("itemId", itemId(reward.item()));
        item.putInt("count", reward.count());
        return item;
    }

    private static ItemReward readReward(NbtCompound item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return parseReward(item.getString("itemId", ""), item.getInt("count", 0));
    }

    private static void migrateLegacyCourseData(NbtCompound root) {
        migrateLegacyCourses(root);
        migrateLegacyPlayers(root);
        migrateLegacyCheckpointProgress(root);
        migrateLegacyActiveRuns(root);
    }

    private static void migrateLegacyCourses(NbtCompound root) {
        CourseConfig course = getOrCreateCourse(DEFAULT_COURSE_ID);

        readDimPosMap(root.getListOrEmpty("customSpawns"), course.customSpawns);
        readDimPosMap(root.getListOrEmpty("checkpoints"), course.checkpoints);

        NbtCompound respawn = root.getCompoundOrEmpty("globalRespawn");
        if (!respawn.isEmpty()) {
            RegistryKey<World> dim = parseWorldKey(respawn.getString("dim", ""));
            BlockPos pos = posFromNbt(respawn.getCompoundOrEmpty("pos"));
            if (dim != null && pos != null) {
                course.respawnDim = dim;
                course.respawnPos = pos;
            }
        }

        if (root.contains("fallY")) {
            course.fallYThreshold = root.getInt("fallY", Integer.MIN_VALUE);
        }
    }

    private static void migrateLegacyPlayers(NbtCompound root) {
        addLegacyUuidSet(root.getListOrEmpty("completed"), COMPLETED_COURSES);
        addLegacyUuidSet(root.getListOrEmpty("discovered"), DISCOVERED_COURSES);
    }

    private static void migrateLegacyCheckpointProgress(NbtCompound root) {
        NbtList list = root.getListOrEmpty("checkpointProgress");
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            RegistryKey<World> dim = parseWorldKey(item.getString("dim", ""));
            int index = item.getInt("index", -1);
            if (playerId != null && dim != null && index >= 0) {
                CHECKPOINT_PROGRESS.putIfAbsent(playerId, new ActiveCheckpoint(DEFAULT_COURSE_ID, index, dim));
            }
        }
    }

    private static void migrateLegacyActiveRuns(NbtCompound root) {
        Map<UUID, Long> timerStart = new ConcurrentHashMap<>();
        readLegacyUuidLongMap(root.getListOrEmpty("timerStart"), timerStart);

        NbtList list = root.getListOrEmpty("activeChests");
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            RegistryKey<World> dim = parseWorldKey(item.getString("dim", ""));
            BlockPos pos = posFromNbt(item.getCompoundOrEmpty("pos"));
            boolean repeatRun = item.getBoolean("timeTrial", false);
            long startTick = timerStart.getOrDefault(playerId, 0L);
            if (playerId != null && dim != null && pos != null) {
                ACTIVE_RUNS.putIfAbsent(playerId, new ActiveRun(DEFAULT_COURSE_ID, pos, dim, startTick, repeatRun));
            }
        }
    }

    private static void addLegacyUuidSet(NbtList list, Map<UUID, Set<String>> target) {
        for (int i = 0; i < list.size(); i++) {
            UUID playerId = parseUuid(list.getCompoundOrEmpty(i).getString("id", ""));
            if (playerId != null) {
                addCourseFlag(target, playerId, DEFAULT_COURSE_ID);
            }
        }
    }

    private static void readLegacyUuidLongMap(NbtList list, Map<UUID, Long> target) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompoundOrEmpty(i);
            UUID playerId = parseUuid(item.getString("id", ""));
            if (playerId != null) {
                target.put(playerId, item.getLong("v", 0L));
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

    private static NbtCompound posToNbt(BlockPos pos) {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("x", pos.getX());
        nbt.putInt("y", pos.getY());
        nbt.putInt("z", pos.getZ());
        return nbt;
    }

    private static BlockPos posFromNbt(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return null;
        }
        return new BlockPos(nbt.getInt("x", 0), nbt.getInt("y", 0), nbt.getInt("z", 0));
    }

    private static RegistryKey<World> parseWorldKey(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(value));
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

    private record TryPlaceResult(boolean success, BlockPos placedPos, Text failureMessage) {
        private static TryPlaceResult success(BlockPos pos) {
            return new TryPlaceResult(true, pos, null);
        }

        private static TryPlaceResult fail(Text message) {
            return new TryPlaceResult(false, null, message);
        }
    }

    private record PendingOffer(String courseId, OfferType type) {}

    private record ActiveRun(String courseId, BlockPos pos, RegistryKey<World> dimension, long startTick, boolean repeatRun) {}

    private record ActiveCheckpoint(String courseId, int index, RegistryKey<World> dimension) {}

    private record RewardTier(int maxSeconds, ItemReward reward) {}

    private record CommandRewardTier(int maxSeconds, List<String> commands) {}

    private record ItemReward(Item item, int count) {}

    private record ResolvedItemReward(ItemReward reward, String claimKey, boolean fallback) {}

    private record ResolvedCommandReward(List<String> commands, String claimKey, boolean fallback) {}

    private record RewardDailyState(String date, int count) {}

    private static final class CourseConfig {
        private final String id;
        private final Map<RegistryKey<World>, List<BlockPos>> customSpawns = new ConcurrentHashMap<>();
        private final Map<RegistryKey<World>, List<BlockPos>> checkpoints = new ConcurrentHashMap<>();
        private final List<RewardTier> rewardTiers = Collections.synchronizedList(new ArrayList<>());
        private final List<CommandRewardTier> commandRewardTiers = Collections.synchronizedList(new ArrayList<>());
        private final List<String> fallbackCommands = Collections.synchronizedList(new ArrayList<>());
        private BlockPos respawnPos;
        private RegistryKey<World> respawnDim;
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
