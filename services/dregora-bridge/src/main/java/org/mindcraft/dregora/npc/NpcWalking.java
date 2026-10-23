package org.mindcraft.dregora.npc;

import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** Validate vanilla walking suggestions, then execute one checked edge at a time. */
final class NpcWalking {
    private final EntityTeammate npc;
    private final NpcTerrain terrain;
    private final Vec3d origin;
    private final List<PathPoint> nodes = new ArrayList<>();
    private final List<Vec3d> feet = new ArrayList<>();
    private int index, stepTicks, stuckTicks;
    private double bestDistance = Double.POSITIVE_INFINITY, cost;
    private NpcWalking(EntityTeammate npc) { this.npc = npc; terrain = new NpcTerrain(npc); origin = npc.getPositionVector(); }
    static NpcWalking plan(EntityTeammate npc, Vec3d goal, double radius) {
        Path path = npc.getNavigator().getPathToXYZ(goal.x, goal.y, goal.z);
        if (path == null || path.getCurrentPathLength() == 0 || path.getCurrentPathLength() > 96) return null;
        NpcWalking route = new NpcWalking(npc);
        Vec3d previous = npc.getPositionVector(), origin = previous;
        for (int i = 0; i < path.getCurrentPathLength(); i++) {
            PathPoint node = path.getPathPointFromIndex(i);
            Vec3d point = route.terrain.surface(node.x + 0.5, node.y, node.z + 0.5);
            if (point == null || Math.abs(point.x - origin.x) > 24 || Math.abs(point.z - origin.z) > 24
                || Math.abs(point.y - origin.y) > 12 || !WalkSafety.edge(route.terrain, previous, point)) return null;
            route.cost += Math.hypot(point.x - previous.x, point.z - previous.z)
                + Math.max(0, point.y - previous.y) * 0.5 + Math.max(0, previous.y - point.y) * 0.25;
            route.nodes.add(node); route.feet.add(point); previous = point;
        }
        if (previous.squareDistanceTo(goal) > radius * radius) return null;
        return route;
    }
    double cost() { return cost; }
    int remaining() { return nodes.size() - index; }
    String tick() {
        if (index >= nodes.size()) return "arrived";
        Vec3d target = feet.get(index), current = npc.getPositionVector();
        Vec3d previous = index == 0 ? origin : feet.get(index - 1);
        if (!terrain.standing(target) || !terrain.clear(current)
            || !WalkSafety.onEdge(previous,target,current)
            || (npc.onGround && !terrain.supportedBody(current))
            || !WalkSafety.edge(terrain, previous, target)) return "terrain_changed";
        double distance = current.squareDistanceTo(target);
        if (distance < 0.20 && npc.onGround) {
            index++; stepTicks = stuckTicks = 0; bestDistance = Double.POSITIVE_INFINITY;
            npc.getNavigator().clearPath(); return remaining() == 0 ? "arrived" : "walking";
        }
        if (++stepTicks > 100 || stuckTicks > 40) return "movement_stalled";
        if (distance + 0.01 < bestDistance) { bestDistance = distance; stuckTicks = 0; } else stuckTicks++;
        if (stepTicks == 1 || stepTicks % 10 == 0) {
            // A single waypoint prevents vanilla shortcutting across unchecked corners or gaps.
            if (!npc.getNavigator().setPath(new Path(new PathPoint[] {nodes.get(index)}), 0.9)) return "waypoint_unreachable";
        }
        return "walking";
    }
}
