package org.mindcraft.dregora.npc;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared server-side entry point for commands and future item/key packets. */
public final class NpcSummoning {
    private NpcSummoning() {}
    public static void request(EntityPlayerMP player, EntityTeammate npc) {
        if (!player.getServer().isCallingFromMinecraftThread()) throw new IllegalStateException("server_thread_required");
        if (!player.getUniqueID().equals(npc.owner()) || player.world != npc.world)
            throw new IllegalArgumentException("npc_not_loaded_or_not_owned");
        if (!player.isEntityAlive() || player.isSpectator() || !npc.isEntityAlive())
            throw new IllegalStateException("actor_unavailable");
        if (npc.summonTicks() > 0) throw new IllegalStateException("summon_pending");
        if (landing(npc, player) == null) throw new IllegalStateException("summon_no_safe_position");
        npc.beginSummon();
    }
    static Vec3d landing(EntityTeammate npc, EntityPlayerMP player) {
        if (!player.isEntityAlive() || player.isSpectator() || player.world != npc.world) return null;
        BlockPos center = new BlockPos(player);
        List<Vec3d> candidates = new ArrayList<>();
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -3; y <= 3; y++) {
            double tx = center.getX() + x + 0.5, tz = center.getZ() + z + 0.5;
            int base = center.getY() + y;
            BlockPos support = new BlockPos(tx, base - 1, tz);
            if (!npc.world.isAreaLoaded(support.add(-1, 0, -1), support.add(1, 3, 1))) continue;
            // Derive the standing height from collision geometry, including slabs and stairs.
            for (AxisAlignedBB box : npc.world.getCollisionBoxes(npc,
                new AxisAlignedBB(tx - 0.01, base - 1, tz - 0.01, tx + 0.01, base, tz + 0.01))) {
                Vec3d target = new Vec3d(tx, box.maxY, tz);
                if (SummonPolicy.withinRange(target.x - player.posX, target.y - player.posY, target.z - player.posZ))
                    candidates.add(target);
            }
        }
        candidates.sort(Comparator.comparingDouble(target -> target.squareDistanceTo(player.getPositionVector())));
        for (Vec3d target : candidates) if (safeLanding(npc, target)
            && npc.world.checkNoEntityCollision(npc.getEntityBoundingBox().offset(
                target.x - npc.posX, target.y - npc.posY, target.z - npc.posZ), npc)) return target;
        return null;
    }
    private static boolean safeLanding(EntityTeammate npc, Vec3d target) {
        BlockPos feet = new BlockPos(target);
        if (target.y < 1 || target.y + npc.height >= npc.world.getHeight()
            || !npc.world.getWorldBorder().contains(feet)) return false;
        AxisAlignedBB body = npc.getEntityBoundingBox().offset(target.x - npc.posX, target.y - npc.posY, target.z - npc.posZ);
        if (!npc.world.getCollisionBoxes(npc, body).isEmpty()) return false;
        for (BlockPos pos : BlockPos.getAllInBox(new BlockPos(body.minX, body.minY - 0.01, body.minZ),
            new BlockPos(body.maxX, body.maxY, body.maxZ))) {
            net.minecraft.block.state.IBlockState state = npc.world.getBlockState(pos);
            net.minecraft.block.Block block = state.getBlock();
            if (state.getMaterial().isLiquid() || block == net.minecraft.init.Blocks.FIRE
                || block == net.minecraft.init.Blocks.CACTUS || block == net.minecraft.init.Blocks.MAGMA
                || block.getRegistryName() == null || !"minecraft".equals(block.getRegistryName().getResourceDomain())) return false;
        }
        return true;
    }
    static boolean teleport(EntityTeammate npc, EntityPlayerMP player, String reason) {
        Vec3d target = landing(npc, player);
        if (target == null) return false;
        particles(npc);
        npc.order("hold", null);
        npc.setPositionAndUpdate(target.x, target.y, target.z);
        npc.motionX = npc.motionY = npc.motionZ = 0;
        npc.fallDistance = 0;
        npc.extinguish();
        npc.setAir(300);
        npc.summonFailed(reason);
        particles(npc);
        return true;
    }
    static void particles(EntityTeammate npc) {
        ((WorldServer) npc.world).spawnParticle(EnumParticleTypes.PORTAL,
            npc.posX, npc.posY + 0.9, npc.posZ, 32, 0.35, 0.8, 0.35, 0.1);
    }
}
