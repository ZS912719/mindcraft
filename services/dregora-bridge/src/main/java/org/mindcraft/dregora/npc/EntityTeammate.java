package org.mindcraft.dregora.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.player.EntityPlayer;
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
    private final ItemStackHandler backpack = new ItemStackHandler(27);
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
    public ItemStackHandler backpack() { return backpack; }
    public void order(String value, Vec3d target) {
        command = value;
        destination = target;
        remainingTicks = "retreat".equals(value) ? 200 : 0;
        getNavigator().clearPath();
        movement = "hold".equals(value) ? "holding" : "requested";
    }
    @Override public void onLivingUpdate() {
        super.onLivingUpdate();
        if (world.isRemote || !isEntityAlive()) return;
        if (remainingTicks > 0) remainingTicks--;
        EntityPlayer player = owner == null ? null : world.getPlayerEntityByUUID(owner);
        boolean ready = player != null && player.isEntityAlive() && !player.isSpectator()
            && player.dimension == dimension && getDistanceSq(player) <= 32 * 32;
        Vec3d target = "follow".equals(command) && ready ? player.getPositionVector() : destination;
        double distance = target == null ? 0 : getPositionVector().squareDistanceTo(target);
        String decision = MovementPolicy.decide(command, ready, distance, remainingTicks);
        if (!"moving".equals(decision)) {
            getNavigator().clearPath();
            movement = decision;
            if ("owner_unavailable".equals(decision)) {
                command = "hold"; destination = null; remainingTicks = 0;
            }
            if ("retreat_expired".equals(decision) || ("retreat".equals(command) && "arrived".equals(decision))) {
                command = "hold";
                destination = null;
            }
            return;
        }
        if (ticksExisted % 10 != 0) return;
        if (!NpcService.safeDestination(this, target)) {
            getNavigator().clearPath();
            movement = "destination_unsafe_or_unloaded";
            return;
        }
        movement = getNavigator().tryMoveToXYZ(target.x, target.y, target.z, 1.0) ? "pathing" : "path_unavailable";
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
