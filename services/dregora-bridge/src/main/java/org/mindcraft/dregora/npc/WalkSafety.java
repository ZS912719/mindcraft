package org.mindcraft.dregora.npc;

import net.minecraft.util.math.Vec3d;

/** Conservative walking sweeps: one block plus thin cover, clearance and support across each edge. */
final class WalkSafety {
    interface Terrain {
        boolean clear(Vec3d feet);
        boolean standing(Vec3d feet);
    }
    private WalkSafety() {}
    static boolean onEdge(Vec3d from, Vec3d to, Vec3d current) {
        double dx = to.x - from.x, dz = to.z - from.z, length = dx * dx + dz * dz;
        double t = length < 1e-9 ? 0 : Math.max(0,Math.min(1,((current.x-from.x)*dx+(current.z-from.z)*dz)/length));
        double offset = Math.hypot(current.x-from.x-t*dx,current.z-from.z-t*dz);
        return Double.isFinite(offset) && Double.isFinite(current.y) && offset <= 0.6
            && current.y >= Math.min(from.y,to.y)-0.1 && current.y <= Math.max(from.y,to.y)+1.3;
    }
    static boolean edge(Terrain terrain, Vec3d from, Vec3d to) {
        double horizontal = Math.hypot(to.x - from.x, to.z - from.z);
        if (!Double.isFinite(horizontal) || !Double.isFinite(to.y - from.y)
            || horizontal > 1.5 || Math.abs(to.y - from.y) > 1.25
            || !terrain.standing(from) || !terrain.standing(to)) return false;
        // Ascents require headroom above the source; descents require clearance above the destination.
        double high = Math.max(from.y, to.y);
        int samples = Math.max(1, (int) Math.ceil(horizontal / 0.15));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            Vec3d sweep = new Vec3d(from.x + (to.x - from.x) * t, high, from.z + (to.z - from.z) * t);
            if (!terrain.clear(sweep)) return false;
            // A level edge may not cut a diagonal corner or cross unsupported ground.
            if (Math.abs(from.y - to.y) < 0.01 && !terrain.standing(sweep)) return false;
        }
        return true;
    }
}
