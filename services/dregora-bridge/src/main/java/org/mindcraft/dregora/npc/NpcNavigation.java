package org.mindcraft.dregora.npc;

import com.google.gson.JsonObject;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.*;
import java.util.*;

/** Server-tick route execution with observed placement effects and bounded recovery. */
final class NpcNavigation {
    private final EntityTeammate npc;
    private LocalRoute search;
    private List<LocalRoute.Step> path=Collections.emptyList();
    private int index, placementIndex, stepTicks, stuckTicks, retries, placed, nextSearchTick, edgeTicks;
    private double bestDistance=Double.POSITIVE_INFINITY;
    private Vec3d plannedGoal;
    private String status="idle", reason="none";
    private final Map<LocalRoute.Point,LocalRoute.Cell> snapshot=new HashMap<>();
    NpcNavigation(EntityTeammate npc) { this.npc=npc; }
    void stopMotion() {
        npc.getNavigator().clearPath();
        npc.getMoveHelper().setMoveTo(npc.posX,npc.posY,npc.posZ,0);
    }
    void reset() {
        search=null; path=Collections.emptyList(); snapshot.clear(); plannedGoal=null;
        index=placementIndex=stepTicks=stuckTicks=retries=placed=nextSearchTick=edgeTicks=0;
        bestDistance=Double.POSITIVE_INFINITY; status="idle"; reason="none";
        stopMotion();
    }
    JsonObject state() {
        JsonObject out=new JsonObject(); out.addProperty("status",status); out.addProperty("reason",reason);
        out.addProperty("placedBlocks",placed); out.addProperty("remainingSteps",Math.max(0,path.size()-index));
        out.addProperty("replans",retries); out.addProperty("expandedNodes",search==null?0:search.expanded());
        if(search!=null && "ready".equals(search.status())) out.addProperty("routeCost",search.cost());
        return out;
    }
    private static LocalRoute.Point point(Vec3d v) { BlockPos p=new BlockPos(v); return new LocalRoute.Point(p.getX(),p.getY(),p.getZ()); }
    private static BlockPos block(LocalRoute.Point p) { return new BlockPos(p.x,p.y,p.z); }
    private LocalRoute.Cell cell(LocalRoute.Point p) {
        BlockPos pos=block(p);
        if(p.y<1 || p.y>=npc.world.getHeight()-1 || !npc.world.isBlockLoaded(pos,false)
            || !npc.world.getWorldBorder().contains(pos)) return LocalRoute.Cell.BLOCKED;
        IBlockState state=npc.world.getBlockState(pos);
        if(npc.world.isAirBlock(pos)) return LocalRoute.Cell.AIR;
        if(state.getMaterial().isLiquid() || state.getBlock()==Blocks.FIRE || state.getBlock()==Blocks.CACTUS
            || state.getBlock()==Blocks.MAGMA || state.getBlock()==Blocks.TNT || state.getBlock()==Blocks.SLIME_BLOCK
            || state.getBlock().getRegistryName()==null || !"minecraft".equals(state.getBlock().getRegistryName().getResourceDomain())) return LocalRoute.Cell.BLOCKED;
        return state.isFullCube() && state.isOpaqueCube() ? LocalRoute.Cell.SOLID : LocalRoute.Cell.BLOCKED;
    }
    private void retry(String failure) {
        stopMotion(); reason=failure; status="replanning";
        search=null; path=Collections.emptyList(); snapshot.clear(); index=placementIndex=stepTicks=stuckTicks=edgeTicks=0;
        plannedGoal=null;
        bestDistance=Double.POSITIVE_INFINITY; nextSearchTick=npc.ticksExisted+20;
        if(!"target_moved".equals(failure) && ++retries>3) status="navigation_failed";
    }
    String tick(Vec3d goal, double arrivalRadius) {
        if("navigation_failed".equals(status)) return status;
        if(plannedGoal!=null && plannedGoal.squareDistanceTo(goal)>4) retry("target_moved");
        if("navigation_failed".equals(status)) return status;
        if(npc.ticksExisted<nextSearchTick) return status;
        if(search==null) {
            plannedGoal=goal;
            LocalRoute.Point start=point(npc.getPositionVector());
            if(cell(start.add(0,-1,0))!=LocalRoute.Cell.SOLID) {
                LocalRoute.Point closest=null; double nearest=Double.POSITIVE_INFINITY;
                for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                    LocalRoute.Point candidate=start.add(dx,0,dz);
                    double distance=npc.getPositionVector().squareDistanceTo(new Vec3d(candidate.x+0.5,candidate.y,candidate.z+0.5));
                    if(cell(candidate)==LocalRoute.Cell.AIR && cell(candidate.add(0,1,0))==LocalRoute.Cell.AIR
                        && cell(candidate.add(0,-1,0))==LocalRoute.Cell.SOLID && distance<nearest) { closest=candidate; nearest=distance; }
                }
                if(closest==null || nearest>2) { retry("start_unsupported"); return status; }
                start=closest;
            }
            search=new LocalRoute(p->snapshot.computeIfAbsent(p,this::cell),start,
                point(goal),NpcBuilding.supplies(npc),Math.max(0,arrivalRadius-1));
            status="planning";
        }
        if("searching".equals(search.status())) {
            npc.getNavigator().clearPath(); search.advance(128,3000000);
            if("searching".equals(search.status())) return status;
            if(!"ready".equals(search.status())) { retry(search.status()); return status; }
            path=search.path(); index=placementIndex=stepTicks=stuckTicks=0;
            bestDistance=Double.POSITIVE_INFINITY;
        }
        if(index>=path.size()) {
            stopMotion();
            if(npc.getPositionVector().squareDistanceTo(goal)>arrivalRadius*arrivalRadius) { retry("target_moved"); return status; }
            status="arrived"; return status;
        }
        LocalRoute.Step step=path.get(index);
        if(placementIndex<step.placements.size()) {
            npc.getNavigator().clearPath();
            status=npc.onGround?"building":"waiting_for_ground";
            if(!npc.onGround || npc.ticksExisted%5!=0) return status;
            BlockPos support=block(step.placements.get(placementIndex));
            if(cell(step.placements.get(placementIndex))==LocalRoute.Cell.SOLID) { placementIndex++; return "building"; }
            String outcome=NpcBuilding.place(npc,support);
            if("placement_no_visible_anchor".equals(outcome) && approachEdge(support)) return "building_edge";
            if(!"placed".equals(outcome)) { retry(outcome); return status; }
            placed++; placementIndex++; edgeTicks=0; status="building"; reason="placement_observed";
            npc.getMoveHelper().setMoveTo(npc.posX,npc.posY,npc.posZ,0);
            return status;
        }
        LocalRoute.Point target=step.target;
        if(cell(target)!=LocalRoute.Cell.AIR || cell(target.add(0,1,0))!=LocalRoute.Cell.AIR
            || cell(target.add(0,-1,0))!=LocalRoute.Cell.SOLID) { retry("terrain_changed"); return status; }
        Vec3d destination=new Vec3d(target.x+0.5,target.y,target.z+0.5);
        double distance=npc.getPositionVector().squareDistanceTo(destination);
        if(distance<0.18 && npc.onGround) {
            index++; placementIndex=stepTicks=stuckTicks=0; bestDistance=Double.POSITIVE_INFINITY;
            npc.getNavigator().clearPath(); status="pathing"; return status;
        }
        stepTicks++;
        if(distance+0.01<bestDistance) { bestDistance=distance; stuckTicks=0; } else stuckTicks++;
        if(stepTicks>100 || stuckTicks>40) { retry("movement_stalled"); return status; }
        if(stepTicks==1 || stepTicks%10==0) {
            if(!npc.getNavigator().tryMoveToXYZ(destination.x,destination.y,destination.z,0.9)) {
                retry("waypoint_unreachable"); return status;
            }
        }
        status="pathing"; return status;
    }
    private boolean approachEdge(BlockPos support) {
        if(++edgeTicks>8 || Math.abs(support.getY()+1-npc.posY)>0.2) return false;
        double dx=support.getX()+0.5-npc.posX,dz=support.getZ()+0.5-npc.posZ;
        double x=npc.posX,z=npc.posZ;
        if(Math.abs(dx)>0.4 && Math.abs(dz)<0.3) x=support.getX()+(dx>0?0.05:0.95);
        else if(Math.abs(dz)>0.4 && Math.abs(dx)<0.3) z=support.getZ()+(dz>0?0.05:0.95);
        else return false;
        AxisAlignedBB body=npc.getEntityBoundingBox().offset(x-npc.posX,0,z-npc.posZ);
        if(!npc.world.getCollisionBoxes(npc,body).isEmpty()
            || npc.world.getCollisionBoxes(npc,body.offset(0,-0.05,0)).isEmpty()) return false;
        npc.getMoveHelper().setMoveTo(x,npc.posY,z,0.35);
        status="building_edge"; return true;
    }
}
