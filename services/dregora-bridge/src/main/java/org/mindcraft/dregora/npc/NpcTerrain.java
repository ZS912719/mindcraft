package org.mindcraft.dregora.npc;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.Comparator;

/** Loaded-world collision geometry, shared by walking and destination checks. */
final class NpcTerrain implements WalkSafety.Terrain {
    private static Boolean auditedBop;
    private final EntityTeammate npc;
    NpcTerrain(EntityTeammate npc) { this.npc = npc; }
    static boolean unsafe(IBlockState state) {
        Block block=state.getBlock();
        return state.getMaterial().isLiquid() || block==Blocks.FIRE || block==Blocks.CACTUS || block==Blocks.MAGMA
            || block==Blocks.TNT || block==Blocks.SLIME_BLOCK || block==Blocks.WEB || block==Blocks.PORTAL
            || block==Blocks.END_PORTAL || block==Blocks.END_GATEWAY || !supportedImplementation(block);
    }
    private static boolean supportedImplementation(Block block) {
        if(block.getRegistryName()==null) return false;
        String namespace=block.getRegistryName().getResourceDomain();
        if("minecraft".equals(namespace)) return true;
        if(!"biomesoplenty".equals(namespace)) return false;
        String implementation=block.getClass().getName();
        if(!"biomesoplenty.common.block.BlockBOPGrass".equals(implementation)
            && !"biomesoplenty.common.block.BlockBOPDirt".equals(implementation)) return false;
        // Admit only the audited implementations and build, not arbitrary subclasses or all BOP blocks.
        if(auditedBop==null) {
            try {
                Class<?> mod=Class.forName("biomesoplenty.core.BiomesOPlenty",false,block.getClass().getClassLoader());
                auditedBop="7.0.1.2445".equals(mod.getField("MOD_VERSION").get(null));
            } catch(ReflectiveOperationException | LinkageError exception) { auditedBop=false; }
        }
        return auditedBop;
    }
    private AxisAlignedBB body(Vec3d feet) {
        return npc.getEntityBoundingBox().offset(feet.x - npc.posX, feet.y - npc.posY, feet.z - npc.posZ);
    }
    @Override public boolean clear(Vec3d feet) {
        if (!Double.isFinite(feet.x) || !Double.isFinite(feet.y) || !Double.isFinite(feet.z)
            || feet.y < 1 || feet.y + npc.height >= npc.world.getHeight()) return false;
        AxisAlignedBB body = body(feet);
        BlockPos low = new BlockPos(body.minX, body.minY - 0.05, body.minZ);
        BlockPos high = new BlockPos(body.maxX, body.maxY, body.maxZ);
        if (!npc.world.isAreaLoaded(low, high) || !npc.world.getWorldBorder().contains(low)
            || !npc.world.getWorldBorder().contains(high)) return false;
        for (BlockPos pos : BlockPos.getAllInBox(low, high)) {
            IBlockState state = npc.world.getBlockState(pos);
            if (unsafe(state)) return false;
        }
        return npc.world.getCollisionBoxes(npc, body.shrink(0.001)).isEmpty();
    }
    @Override public boolean standing(Vec3d feet) {
        if (!clear(feet)) return false;
        // Require support under the center, not merely a neighboring collision at the body's edge.
        AxisAlignedBB probe = new AxisAlignedBB(feet.x - 0.08, feet.y - 0.06, feet.z - 0.08,
            feet.x + 0.08, feet.y + 0.001, feet.z + 0.08);
        return npc.world.getCollisionBoxes(npc, probe).stream().anyMatch(box -> Math.abs(box.maxY - feet.y) < 0.06);
    }
    boolean supportedBody(Vec3d feet) {
        if (!clear(feet)) return false;
        AxisAlignedBB body = body(feet);
        AxisAlignedBB probe = new AxisAlignedBB(body.minX + 0.01, feet.y - 0.06, body.minZ + 0.01,
            body.maxX - 0.01, feet.y + 0.001, body.maxZ - 0.01);
        return npc.world.getCollisionBoxes(npc, probe).stream().anyMatch(box -> Math.abs(box.maxY - feet.y) < 0.06);
    }
    Vec3d surface(double x, double y, double z) {
        BlockPos low = new BlockPos(x - 1, y - 1, z - 1), high = new BlockPos(x + 1, y + 3, z + 1);
        if (!npc.world.isAreaLoaded(low, high)) return null;
        return npc.world.getCollisionBoxes(npc, new AxisAlignedBB(x - 0.08, y - 1, z - 0.08, x + 0.08, y + 0.5, z + 0.08))
            .stream().map(box -> new Vec3d(x, box.maxY, z))
            .filter(point -> point.y >= y - 1 && point.y <= y + 0.5 && standing(point))
            .min(Comparator.comparingDouble(point -> Math.abs(point.y - y))).orElse(null);
    }
}
