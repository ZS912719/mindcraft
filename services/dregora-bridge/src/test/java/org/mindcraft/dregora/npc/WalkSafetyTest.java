package org.mindcraft.dregora.npc;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class WalkSafetyTest {
    private static final class Geometry implements WalkSafety.Terrain {
        final List<AxisAlignedBB> blocks = new ArrayList<>();
        void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            blocks.add(new AxisAlignedBB(minX,minY,minZ,maxX,maxY,maxZ));
        }
        @Override public boolean clear(Vec3d point) {
            AxisAlignedBB body = new AxisAlignedBB(point.x-.3,point.y+.001,point.z-.3,point.x+.3,point.y+1.799,point.z+.3);
            return blocks.stream().noneMatch(box -> box.intersects(body));
        }
        @Override public boolean standing(Vec3d point) {
            return clear(point) && blocks.stream().anyMatch(box -> Math.abs(box.maxY-point.y)<.001
                && point.x>box.minX && point.x<box.maxX && point.z>box.minZ && point.z<box.maxZ);
        }
    }
    @Test public void acceptsSlabSnowAndSingleBlockSlopesInBothDirections() {
        for (double rise : new double[] {.0625,.125,.5,1,1.0625,1.25}) {
            Geometry terrain = new Geometry();
            terrain.box(0,0,0,1,1,1); terrain.box(1,0,0,2,1+rise,1);
            Vec3d low = new Vec3d(.5,1,.5), high = new Vec3d(1.5,1+rise,.5);
            assertTrue(WalkSafety.edge(terrain,low,high));
            assertTrue(WalkSafety.edge(terrain,high,low));
        }
    }
    @Test public void refusesDeepDropsAndHeadroomBlockedAscents() {
        Geometry terrain = new Geometry();
        terrain.box(0,0,0,1,1,1); terrain.box(1,0,0,2,3,1);
        assertFalse(WalkSafety.edge(terrain,new Vec3d(.5,1,.5),new Vec3d(1.5,3,.5)));
        assertFalse(WalkSafety.edge(terrain,new Vec3d(1.5,3,.5),new Vec3d(.5,1,.5)));
        Geometry tooHigh = new Geometry(); tooHigh.box(0,0,0,1,1,1); tooHigh.box(1,0,0,2,2.251,1);
        assertFalse(WalkSafety.edge(tooHigh,new Vec3d(.5,1,.5),new Vec3d(1.5,2.251,.5)));
        Geometry ceiling = new Geometry();
        ceiling.box(0,0,0,1,1,1); ceiling.box(1,0,0,2,2,1); ceiling.box(0,3,0,1,4,1);
        assertFalse(WalkSafety.edge(ceiling,new Vec3d(.5,1,.5),new Vec3d(1.5,2,.5)));
    }
    @Test public void refusesUnsupportedEdgesAndDiagonalCornerCutting() {
        Geometry gap = new Geometry();
        gap.box(0,0,0,.8,1,1); gap.box(1.2,0,0,2,1,1);
        assertFalse(WalkSafety.edge(gap,new Vec3d(.5,1,.5),new Vec3d(1.5,1,.5)));
        Geometry corner = new Geometry();
        corner.box(0,0,0,2,1,2); corner.box(1,1,0,2,3,1);
        assertFalse(WalkSafety.edge(corner,new Vec3d(.5,1,.5),new Vec3d(1.5,1,1.5)));
    }
    @Test public void changedTerrainInvalidatesAPreviouslySafeEdge() {
        Geometry terrain = new Geometry(); terrain.box(0,0,0,2,1,1);
        Vec3d from = new Vec3d(.5,1,.5), to = new Vec3d(1.5,1,.5);
        assertTrue(WalkSafety.edge(terrain,from,to));
        terrain.box(1,1,0,2,3,1);
        assertFalse(WalkSafety.edge(terrain,from,to));
    }
    @Test public void steppingOntoASlabCanRaiseFeetBeforeTheCenterCrossesItsEdge() {
        Vec3d from=new Vec3d(.5,1,.5),to=new Vec3d(1.5,1.5,.5);
        assertTrue(WalkSafety.onEdge(from,to,new Vec3d(.91,1.5,.5)));
        assertTrue(WalkSafety.onEdge(from,to,new Vec3d(1.4,2.2,.5)));
        assertFalse(WalkSafety.onEdge(from,to,new Vec3d(.9,1.5,1.2)));
        assertFalse(WalkSafety.onEdge(from,to,new Vec3d(.9,.5,.5)));
        assertFalse(WalkSafety.onEdge(from,to,new Vec3d(.9,4,.5)));
    }
}
