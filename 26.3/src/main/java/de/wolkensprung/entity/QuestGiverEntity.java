package de.wolkensprung.entity;

import de.wolkensprung.quest.WolkensprungQuest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Quest NPC for Wolkensprung; with patrol radius 0 it stays still, otherwise it roams near home.
 */
public class QuestGiverEntity extends PathfinderMob {
    private static final int DEFAULT_WANDER_RADIUS = 0;
    private static final double PATROL_SPEED = 0.6;
    private static final EntityDataAccessor<String> SKIN_VALUE = SynchedEntityData.defineId(QuestGiverEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> COURSE_ID = SynchedEntityData.defineId(QuestGiverEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> WANDER_RADIUS = SynchedEntityData.defineId(QuestGiverEntity.class, EntityDataSerializers.INT);
    private BlockPos homePos;
    private int wanderCooldown;

    public QuestGiverEntity(EntityType<? extends PathfinderMob> entityType, Level world) {
        super(entityType, world);
        this.setPersistenceRequired();
        this.setPermanentlyInvulnerable(true);
        this.homePos = this.blockPosition();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN_VALUE, "");
        builder.define(COURSE_ID, WolkensprungQuest.DEFAULT_COURSE_ID);
        builder.define(WANDER_RADIUS, DEFAULT_WANDER_RADIUS);
    }

    @Override
    protected void registerGoals() {
        // Patrol movement is handled directly in tickMovement so it always stays centered on homePos.
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.homePos == null) {
            this.homePos = this.blockPosition();
        }
        if (getWanderRadius() <= 0) {
            this.getNavigation().stop();
            this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
            this.xxa = 0;
            this.zza = 0;
            // Gentle idle movement so the model is not perfectly stiff.
            this.walkAnimation.update(0.1f, 0.2f, 0.0f);
            return;
        }

        tickPatrolMovement();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            net.minecraft.server.level.ServerLevel serverWorld = (net.minecraft.server.level.ServerLevel) serverPlayer.level();
            // Face the player during dialog to feel responsive.
            this.getLookControl().setLookAt(serverPlayer.getX(), serverPlayer.getEyeY(), serverPlayer.getZ());
            InteractionResult result = WolkensprungQuest.onVillagerInteract(serverWorld, serverPlayer, hand, this);
            if (result == InteractionResult.PASS) {
                return InteractionResult.PASS;
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.SUCCESS;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput data) {
        super.addAdditionalSaveData(data);
        data.putString("SkinValue", getSkinValue());
        data.putString("CourseId", getCourseId());
        data.putInt("WanderRadius", getWanderRadius());
        if (this.homePos != null) {
            data.putInt("HomeX", this.homePos.getX());
            data.putInt("HomeY", this.homePos.getY());
            data.putInt("HomeZ", this.homePos.getZ());
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput data) {
        super.readAdditionalSaveData(data);
        setSkinValue(data.getStringOr("SkinValue", ""));
        setCourseId(data.getStringOr("CourseId", WolkensprungQuest.DEFAULT_COURSE_ID));
        setWanderRadius(data.getIntOr("WanderRadius", DEFAULT_WANDER_RADIUS));
        var homeX = data.getInt("HomeX");
        var homeY = data.getInt("HomeY");
        var homeZ = data.getInt("HomeZ");
        if (homeX.isPresent() && homeY.isPresent() && homeZ.isPresent()) {
            this.homePos = new BlockPos(homeX.get(), homeY.get(), homeZ.get());
        }
    }

    public String getSkinValue() {
        return this.entityData.get(SKIN_VALUE);
    }

    public void setSkinValue(String value) {
        this.entityData.set(SKIN_VALUE, value == null ? "" : value);
    }

    public String getCourseId() {
        return WolkensprungQuest.normalizeCourseId(this.entityData.get(COURSE_ID));
    }

    public void setCourseId(String value) {
        this.entityData.set(COURSE_ID, WolkensprungQuest.normalizeCourseId(value));
    }

    public int getWanderRadius() {
        return Math.max(0, this.entityData.get(WANDER_RADIUS));
    }

    public void setWanderRadius(int value) {
        this.entityData.set(WANDER_RADIUS, Math.max(0, value));
        this.wanderCooldown = 0;
    }

    public BlockPos getHomePosSafe() {
        if (this.homePos == null) {
            this.homePos = this.blockPosition();
        }
        return this.homePos;
    }

    public void setHomePos(BlockPos pos) {
        if (pos != null) {
            this.homePos = pos.immutable();
            this.wanderCooldown = 0;
        }
    }

    private void tickPatrolMovement() {
        BlockPos home = getHomePosSafe();
        double maxDistance = Math.max(1, getWanderRadius()) + 1.5;
        if (this.distanceToSqr(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5) > maxDistance * maxDistance) {
            this.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5, PATROL_SPEED);
            return;
        }
        if (!this.getNavigation().isDone()) {
            return;
        }
        if (this.wanderCooldown > 0) {
            this.wanderCooldown--;
            return;
        }

        this.wanderCooldown = startPatrolStep(home, getWanderRadius()) ? 60 + this.random.nextInt(60) : 20;
    }

    private boolean startPatrolStep(BlockPos home, int radius) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = this.random.nextInt(radius * 2 + 1) - radius;
            int dz = this.random.nextInt(radius * 2 + 1) - radius;
            if (dx == 0 && dz == 0) {
                continue;
            }

            BlockPos feetPos = findPatrolFeetPos(home, dx, dz);
            if (feetPos == null) {
                continue;
            }
            if (this.getNavigation().moveTo(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5, PATROL_SPEED)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findPatrolFeetPos(BlockPos home, int dx, int dz) {
        Level world = this.level();
        int targetX = home.getX() + dx;
        int targetZ = home.getZ() + dz;
        for (int y = home.getY() + 2; y >= home.getY() - 3; y--) {
            BlockPos basePos = new BlockPos(targetX, y, targetZ);
            BlockState baseState = world.getBlockState(basePos);
            if (!Block.isShapeFullBlock(baseState.getCollisionShape(world, basePos))) {
                continue;
            }

            BlockPos feetPos = basePos.above();
            BlockPos headPos = feetPos.above();
            if (!world.isEmptyBlock(feetPos) || !world.isEmptyBlock(headPos)) {
                continue;
            }
            return feetPos.immutable();
        }
        return null;
    }

}

