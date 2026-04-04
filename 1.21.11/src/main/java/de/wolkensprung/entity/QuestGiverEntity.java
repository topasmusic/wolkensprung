package de.wolkensprung.entity;

import de.wolkensprung.quest.WolkensprungQuest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;

/**
 * Quest NPC for Wolkensprung; with patrol radius 0 it stays still, otherwise it roams near home.
 */
public class QuestGiverEntity extends PathAwareEntity {
    private static final int DEFAULT_WANDER_RADIUS = 0;
    private static final double PATROL_SPEED = 0.6;
    private static final TrackedData<String> SKIN_VALUE = DataTracker.registerData(QuestGiverEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> COURSE_ID = DataTracker.registerData(QuestGiverEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Integer> WANDER_RADIUS = DataTracker.registerData(QuestGiverEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private BlockPos homePos;
    private int wanderCooldown;

    public QuestGiverEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        this.setPersistent();
        this.setInvulnerable(true);
        this.homePos = this.getBlockPos();
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(SKIN_VALUE, "");
        builder.add(COURSE_ID, WolkensprungQuest.DEFAULT_COURSE_ID);
        builder.add(WANDER_RADIUS, DEFAULT_WANDER_RADIUS);
    }

    @Override
    protected void initGoals() {
        // Patrol movement is handled directly in tickMovement so it always stays centered on homePos.
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        if (this.homePos == null) {
            this.homePos = this.getBlockPos();
        }
        if (getWanderRadius() <= 0) {
            this.getNavigation().stop();
            this.setVelocity(0, this.getVelocity().y, 0);
            this.sidewaysSpeed = 0;
            this.forwardSpeed = 0;
            // Gentle idle movement so the model is not perfectly stiff.
            this.limbAnimator.updateLimbs(0.1f, 0.2f, 0.0f);
            return;
        }

        tickPatrolMovement();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            net.minecraft.server.world.ServerWorld serverWorld = (net.minecraft.server.world.ServerWorld) serverPlayer.getEntityWorld();
            // Face the player during dialog to feel responsive.
            this.getLookControl().lookAt(serverPlayer.getX(), serverPlayer.getEyeY(), serverPlayer.getZ());
            ActionResult result = WolkensprungQuest.onVillagerInteract(serverWorld, serverPlayer, hand, this);
            if (result == ActionResult.PASS) {
                return ActionResult.PASS;
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.SUCCESS;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 20.0);
    }

    @Override
    protected void writeCustomData(WriteView data) {
        super.writeCustomData(data);
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
    protected void readCustomData(ReadView data) {
        super.readCustomData(data);
        setSkinValue(data.getString("SkinValue", ""));
        setCourseId(data.getString("CourseId", WolkensprungQuest.DEFAULT_COURSE_ID));
        setWanderRadius(data.getInt("WanderRadius", DEFAULT_WANDER_RADIUS));
        var homeX = data.getOptionalInt("HomeX");
        var homeY = data.getOptionalInt("HomeY");
        var homeZ = data.getOptionalInt("HomeZ");
        if (homeX.isPresent() && homeY.isPresent() && homeZ.isPresent()) {
            this.homePos = new BlockPos(homeX.get(), homeY.get(), homeZ.get());
        }
    }

    public String getSkinValue() {
        return this.dataTracker.get(SKIN_VALUE);
    }

    public void setSkinValue(String value) {
        this.dataTracker.set(SKIN_VALUE, value == null ? "" : value);
    }

    public String getCourseId() {
        return WolkensprungQuest.normalizeCourseId(this.dataTracker.get(COURSE_ID));
    }

    public void setCourseId(String value) {
        this.dataTracker.set(COURSE_ID, WolkensprungQuest.normalizeCourseId(value));
    }

    public int getWanderRadius() {
        return Math.max(0, this.dataTracker.get(WANDER_RADIUS));
    }

    public void setWanderRadius(int value) {
        this.dataTracker.set(WANDER_RADIUS, Math.max(0, value));
        this.wanderCooldown = 0;
    }

    public BlockPos getHomePosSafe() {
        if (this.homePos == null) {
            this.homePos = this.getBlockPos();
        }
        return this.homePos;
    }

    public void setHomePos(BlockPos pos) {
        if (pos != null) {
            this.homePos = pos.toImmutable();
            this.wanderCooldown = 0;
        }
    }

    private void tickPatrolMovement() {
        BlockPos home = getHomePosSafe();
        double maxDistance = Math.max(1, getWanderRadius()) + 1.5;
        if (this.squaredDistanceTo(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5) > maxDistance * maxDistance) {
            this.getNavigation().startMovingTo(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5, PATROL_SPEED);
            return;
        }
        if (!this.getNavigation().isIdle()) {
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
            if (this.getNavigation().startMovingTo(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5, PATROL_SPEED)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findPatrolFeetPos(BlockPos home, int dx, int dz) {
        World world = this.getEntityWorld();
        int targetX = home.getX() + dx;
        int targetZ = home.getZ() + dz;
        for (int y = home.getY() + 2; y >= home.getY() - 3; y--) {
            BlockPos basePos = new BlockPos(targetX, y, targetZ);
            BlockState baseState = world.getBlockState(basePos);
            if (!Block.isShapeFullCube(baseState.getCollisionShape(world, basePos))) {
                continue;
            }

            BlockPos feetPos = basePos.up();
            BlockPos headPos = feetPos.up();
            if (!world.isAir(feetPos) || !world.isAir(headPos)) {
                continue;
            }
            return feetPos.toImmutable();
        }
        return null;
    }

}

