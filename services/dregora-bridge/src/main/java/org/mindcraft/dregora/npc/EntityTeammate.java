package org.mindcraft.dregora.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.block.material.Material;
import net.minecraft.util.DamageSource;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;
import java.util.UUID;

public final class EntityTeammate extends EntityCreature {
    private UUID owner;
    private String command = "hold";
    private String movement = "holding";
    private Vec3d destination;
    private int remainingTicks;
    private int summonTicks;
    private int rescueRetryTicks;
    private int followRecoveryRetryTicks;
    private final ItemStackHandler backpack = new ItemStackHandler(27);
    private final NpcNavigation navigation = new NpcNavigation(this);
    public EntityTeammate(World world) {
        super(world);
        setSize(0.6F, 1.8F);
        enablePersistence();
        setCanPickUpLoot(false);
        setCustomNameTag("Mindcraft Teammate");
        setAlwaysRenderNameTag(true);
        setPathPriority(PathNodeType.WATER, -1);
        setPathPriority(PathNodeType.LAVA, -1);
        setPathPriority(PathNodeType.DAMAGE_FIRE, -1);
        setPathPriority(PathNodeType.DANGER_FIRE, -1);
    }
    @Override protected void initEntityAI() { tasks.addTask(0, new EntityAISwimming(this)); }
    @Override protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20);
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.25);
    }
    @Override public boolean attackEntityAsMob(Entity entity) { return false; }
    public UUID owner() { return owner; }
    public void setOwner(UUID value) { owner = value; }
    public String command() { return command; }
    public String movement() { return movement; }
    public int summonTicks() { return summonTicks; }
    void beginSummon() { order("summon", null); summonTicks = SummonPolicy.SUMMON_TICKS; movement = "summon_waiting"; }
    void summonFailed(String reason) { summonTicks = 0; movement = reason; }
    public ItemStackHandler backpack() { return backpack; }
    public com.google.gson.JsonObject navigationState() { return navigation.state(); }
    void suppliesChanged() { navigation.reset(); }
    public void order(String value, Vec3d target) {
        summonTicks = 0;
        command = value;
        destination = target;
        remainingTicks = "retreat".equals(value) ? 200 : "navigate".equals(value) ? 1200 : 0;
        getNavigator().clearPath();
        navigation.reset();
        movement = "hold".equals(value) ? "holding" : "requested";
    }
    @Override public void onLivingUpdate() {
        super.onLivingUpdate();
        if (world.isRemote || !isEntityAlive()) return;
        if (rescueRetryTicks > 0) rescueRetryTicks--;
        if (followRecoveryRetryTicks > 0) followRecoveryRetryTicks--;
        if (SummonPolicy.emergency(isInLava(), isInsideOfMaterial(Material.WATER), getAir(), isBurning(), getHealth(), false)
            && rescueRetryTicks == 0) {
            rescueRetryTicks = 20;
            if (rescue()) return;
        }
        if ("summon".equals(command)) {
            getNavigator().clearPath();
            EntityPlayer player = owner == null ? null : world.getPlayerEntityByUUID(owner);
            if (!(player instanceof EntityPlayerMP) || !player.isEntityAlive() || player.isSpectator()) {
                order("hold", null); movement = "owner_unavailable"; return;
            }
            if (summonTicks > 0) {
                summonTicks--;
                if (summonTicks % 10 == 0) NpcSummoning.particles(this);
            }
            if (summonTicks == 0) {
                if (!NpcSummoning.teleport(this, (EntityPlayerMP) player, "summoned")) {
                    order("hold", null); movement = "summon_no_safe_position";
                }
            }
            return;
        }
        if (remainingTicks > 0) remainingTicks--;
        EntityPlayer player = owner == null ? null : world.getPlayerEntityByUUID(owner);
        boolean ready = player != null && player.isEntityAlive() && !player.isSpectator()
            && player.dimension == dimension
            && ("follow".equals(command) || getDistanceSq(player) <= MovementPolicy.LOCAL_RANGE * MovementPolicy.LOCAL_RANGE);
        Vec3d target = "follow".equals(command) && ready ? player.getPositionVector() : destination;
        double distance = target == null ? 0 : getPositionVector().squareDistanceTo(target);
        // Distance alone does not invalidate ownership: a distant follower waits for a manual summon.
        if (MovementPolicy.recoverFollow(command, ready, distance, followRecoveryRetryTicks,
            getLeashed() || isRiding() || isBeingRidden()) && player instanceof EntityPlayerMP) {
            followRecoveryRetryTicks = MovementPolicy.FOLLOW_RECOVERY_RETRY_TICKS;
            if (NpcSummoning.recoverFollow(this, (EntityPlayerMP) player)) return;
        }
        String decision = MovementPolicy.decide(command, ready, distance, remainingTicks);
        if (!"moving".equals(decision)) {
            navigation.stopMotion();
            movement = decision;
            if ("owner_unavailable".equals(decision)) {
                command = "hold"; destination = null; remainingTicks = 0;
            }
            if ("retreat_expired".equals(decision) || "navigation_expired".equals(decision)
                || (("retreat".equals(command) || "navigate".equals(command)) && "arrived".equals(decision))) {
                command = "hold";
                destination = null;
            }
            return;
        }
        if (!NpcService.safeDestination(this, target)) {
            navigation.stopMotion();
            movement = "destination_unsafe_or_unloaded";
            return;
        }
        double radius = "follow".equals(command) ? 3 : "navigate".equals(command) ? 1 : 2;
        movement = navigation.tick(target, radius);
    }
    private boolean rescue() {
        EntityPlayer player = owner == null ? null : world.getPlayerEntityByUUID(owner);
        boolean rescued = player instanceof EntityPlayerMP
            && NpcSummoning.teleport(this, (EntityPlayerMP) player, "emergency_summoned");
        if (rescued) rescueRetryTicks = 0;
        return rescued;
    }
    @Override public boolean attackEntityFrom(DamageSource source, float amount) {
        if (!world.isRemote && isEntityAlive()) {
            boolean environmental = source == DamageSource.DROWN || source == DamageSource.LAVA
                || source == DamageSource.IN_FIRE || source == DamageSource.ON_FIRE || source == DamageSource.HOT_FLOOR
                || source == DamageSource.FALL || source == DamageSource.IN_WALL || source == DamageSource.OUT_OF_WORLD;
            boolean danger = source == DamageSource.LAVA || source == DamageSource.DROWN
                || (environmental && amount >= getHealth());
            if (danger && (rescueRetryTicks == 0 || amount >= getHealth())) {
                rescueRetryTicks = 20;
                if (rescue()) return false;
            }
        }
        return super.attackEntityFrom(source, amount);
    }
    @Override public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (!world.isRemote && dead) NpcRespawns.schedule(this);
    }
    @Override public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        if (owner != null) tag.setUniqueId("MindcraftOwner", owner);
        tag.setTag("MindcraftBackpack", backpack.serializeNBT());
    }
    @Override protected void dropEquipment(boolean recentlyHit, int looting) {
        super.dropEquipment(recentlyHit, looting);
        for (int i = 0; i < backpack.getSlots(); i++) {
            net.minecraft.item.ItemStack stack = backpack.getStackInSlot(i);
            if (!stack.isEmpty()) entityDropItem(stack.copy(), 0);
            backpack.setStackInSlot(i, net.minecraft.item.ItemStack.EMPTY);
        }
    }
    @Override public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag);
        owner = tag.hasUniqueId("MindcraftOwner") ? tag.getUniqueId("MindcraftOwner") : null;
        if (tag.hasKey("MindcraftBackpack", 10)) backpack.deserializeNBT(tag.getCompoundTag("MindcraftBackpack"));
        // Do not resume a movement order after a save, unload or process restart.
        order("hold", null);
    }
}
