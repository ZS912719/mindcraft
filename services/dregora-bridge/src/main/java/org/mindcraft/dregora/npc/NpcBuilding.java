package org.mindcraft.dregora.npc;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.Event;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class NpcBuilding {
    private NpcBuilding() {}
    static boolean usable(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) return false;
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        IBlockState state = block.getStateFromMeta(((ItemBlock) stack.getItem()).getMetadata(stack.getMetadata()));
        if (block.getRegistryName() == null || !"minecraft".equals(block.getRegistryName().getResourceDomain())
            || !state.isFullCube() || !state.isOpaqueCube() || block instanceof BlockFalling
            || block.hasTileEntity(state) || block == Blocks.MAGMA || block == Blocks.SLIME_BLOCK
            || block == Blocks.TNT || stack.hasTagCompound()) return false;
        if (!Loader.isModLoaded("reskillable")) return true;
        try {
            Object holder = Class.forName("codersafterdark.reskillable.base.LevelLockHandler")
                .getMethod("getSkillLock", ItemStack.class).invoke(null, stack);
            // No owner/FakePlayer skill data is used to authorize NPC construction.
            // hasNone denotes a native NoneRequirement marker, not an empty requirement list.
            return ((java.util.Collection<?>)holder.getClass().getMethod("getRequirements").invoke(holder)).isEmpty();
        } catch (Exception | LinkageError unavailable) { return false; }
    }
    static int supplies(EntityTeammate npc) {
        int count = 0;
        for (int i=0;i<npc.backpack().getSlots();i++) {
            ItemStack stack = npc.backpack().getStackInSlot(i);
            if (usable(stack)) count += stack.getCount();
        }
        return count;
    }
    static String place(EntityTeammate npc, BlockPos target) {
        WorldServer world = (WorldServer) npc.world;
        if (!world.isAreaLoaded(target.add(-1,-1,-1),target.add(1,2,1)) || !world.getWorldBorder().contains(target)) return "placement_unloaded";
        if (!world.isAirBlock(target)) return "placement_obstructed";
        if (npc.getPositionEyes(1).squareDistanceTo(new Vec3d(target).addVector(0.5,0.5,0.5)) > 4.5*4.5) return "placement_out_of_reach";
        if (!world.checkNoEntityCollision(new AxisAlignedBB(target))) return "placement_entity_collision";
        int slot = -1;
        for (int i=0;i<npc.backpack().getSlots();i++) if (usable(npc.backpack().getStackInSlot(i))) { slot=i; break; }
        if (slot<0) return "building_materials_unavailable";
        ItemStack source = npc.backpack().getStackInSlot(slot), working = source.copy();
        int beforeCount=source.getCount();
        UUID identity = UUID.nameUUIDFromBytes(("mindcraft-builder:"+npc.getUniqueID()).getBytes(StandardCharsets.UTF_8));
        FakePlayer actor = FakePlayerFactory.get(world,new GameProfile(identity,"[MindcraftNPC]"));
        actor.setPosition(npc.posX,npc.posY,npc.posZ);
        actor.capabilities.isCreativeMode=false; actor.capabilities.allowEdit=true;
        actor.inventory.currentItem=0; actor.setHeldItem(EnumHand.MAIN_HAND,working);
        try {
            for (EnumFacing direction : EnumFacing.values()) {
                BlockPos anchor = target.offset(direction.getOpposite());
                IBlockState anchorState = world.getBlockState(anchor);
                if (!anchorState.isFullCube() || !anchorState.isOpaqueCube() || anchorState.getBlock().hasTileEntity(anchorState)) continue;
                Vec3d hit = new Vec3d(anchor).addVector(0.5+direction.getFrontOffsetX()*0.499,
                    0.5+direction.getFrontOffsetY()*0.499,0.5+direction.getFrontOffsetZ()*0.499);
                RayTraceResult trace=world.rayTraceBlocks(npc.getPositionEyes(1),hit,false,true,false);
                if (trace==null || trace.typeOfHit!=RayTraceResult.Type.BLOCK || !anchor.equals(trace.getBlockPos()) || trace.sideHit!=direction) continue;
                npc.getLookHelper().setLookPosition(hit.x,hit.y,hit.z,30,30);
                if (!world.isBlockModifiable(actor,anchor) || npc.getServer().isBlockProtected(world,anchor,actor)
                    || !actor.canPlayerEdit(anchor,direction,working)) return "placement_protected";
                PlayerInteractEvent.RightClickBlock event=ForgeHooks.onRightClickBlock(actor,EnumHand.MAIN_HAND,anchor,direction,hit);
                if (event.isCanceled() || event.getUseItem()==Event.Result.DENY) return "placement_event_denied";
                EnumActionResult result=working.onItemUse(actor,world,anchor,EnumHand.MAIN_HAND,direction,
                    (float)(hit.x-anchor.getX()),(float)(hit.y-anchor.getY()),(float)(hit.z-anchor.getZ()));
                boolean changed=world.getBlockState(target).getBlock()==((ItemBlock)source.getItem()).getBlock()
                    && world.getBlockState(target).isFullCube();
                if (changed) {
                    // The real inventory owns the resource; the helper holds only a temporary copy.
                    npc.backpack().extractItem(slot,1,false);
                    return result==EnumActionResult.SUCCESS && working.getCount()==beforeCount-1
                        ? "placed" : "placement_unconfirmed";
                }
                return "placement_rejected";
            }
            return "placement_no_visible_anchor";
        } catch (RuntimeException | LinkageError failure) {
            if (world.getBlockState(target).getBlock()==((ItemBlock)source.getItem()).getBlock()
                && world.getBlockState(target).isFullCube()) {
                npc.backpack().extractItem(slot,1,false);
                return "placement_unconfirmed";
            }
            return "placement_runtime_error";
        } finally { actor.setHeldItem(EnumHand.MAIN_HAND,ItemStack.EMPTY); }
    }
    static void supply(EntityPlayerMP owner, EntityTeammate npc, int slot, int count) {
        if (!owner.getServer().isCallingFromMinecraftThread() || !owner.getUniqueID().equals(npc.owner())
            || owner.world!=npc.world || !owner.isEntityAlive() || owner.isSpectator()
            || !npc.isEntityAlive() || npc.getDistanceSq(owner)>36) throw new IllegalStateException("supply_actor_unavailable");
        if (slot<0 || slot>35 || count<1 || count>64) throw new IllegalArgumentException("invalid_supply");
        ItemStack source=owner.inventory.getStackInSlot(slot);
        if (source.isEmpty() || source.getCount()<count || !usable(source)) throw new IllegalArgumentException("building_materials_unavailable");
        ItemStack transfer=source.copy(); transfer.setCount(count);
        ItemStack remainder=transfer.copy();
        for (int i=0;i<npc.backpack().getSlots();i++) remainder=npc.backpack().insertItem(i,remainder,true);
        if (!remainder.isEmpty()) throw new IllegalStateException("npc_inventory_full");
        for (int i=0;i<npc.backpack().getSlots();i++) transfer=npc.backpack().insertItem(i,transfer,false);
        source.shrink(count); owner.inventory.markDirty(); owner.inventoryContainer.detectAndSendChanges();
        npc.suppliesChanged();
    }
}
