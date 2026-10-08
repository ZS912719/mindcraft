package org.mindcraft.dregora.npc;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LocalRouteTest {
    private static final class Grid implements LocalRoute.Terrain {
        final Map<LocalRoute.Point,LocalRoute.Cell> changes=new HashMap<>();
        final int width;
        Grid(int width) { this.width=width; }
        @Override public LocalRoute.Cell cell(LocalRoute.Point p) {
            if(p.x<0 || p.x>7 || Math.abs(p.z)>width || p.y<0 || p.y>7) return LocalRoute.Cell.BLOCKED;
            return changes.getOrDefault(p,p.y==0?LocalRoute.Cell.SOLID:LocalRoute.Cell.AIR);
        }
        void set(int x,int y,int z,LocalRoute.Cell cell) { changes.put(new LocalRoute.Point(x,y,z),cell); }
    }
    private LocalRoute solve(Grid grid,LocalRoute.Point goal,int materials) {
        LocalRoute route=new LocalRoute(grid,new LocalRoute.Point(0,1,0),goal,materials,0);
        for(int i=0;i<10000 && "searching".equals(route.status());i++) route.advance(128,100000000);
        return route;
    }
    private int placements(LocalRoute route) { return route.path().stream().mapToInt(step->step.placements.size()).sum(); }
    @Test public void choosesAFreeDetourInsteadOfBuildingOverAnObstacle() {
        Grid grid=new Grid(1);
        grid.set(3,1,0,LocalRoute.Cell.SOLID); grid.set(3,2,0,LocalRoute.Cell.SOLID);
        LocalRoute route=solve(grid,new LocalRoute.Point(6,1,0),12);
        assertEquals("ready",route.status()); assertEquals(8,route.cost(),0.001);
        assertEquals(0,placements(route)); assertTrue(route.path().stream().anyMatch(s->s.target.z!=0));
    }
    @Test public void bridgesAThreeBlockGapAndAccountsForEveryMaterial() {
        Grid grid=new Grid(0); for(int x=2;x<=4;x++) grid.set(x,0,0,LocalRoute.Cell.AIR);
        LocalRoute route=solve(grid,new LocalRoute.Point(6,1,0),3);
        assertEquals("ready",route.status()); assertEquals(3,placements(route)); assertEquals(18,route.cost(),0.001);
        Set<LocalRoute.Point> built=new HashSet<>();
        for(LocalRoute.Step step:route.path()) {
            for(LocalRoute.Point p:step.placements) assertTrue(built.add(p));
            LocalRoute.Point below=step.target.add(0,-1,0);
            assertTrue(built.contains(below) || grid.cell(below)==LocalRoute.Cell.SOLID);
        }
    }
    @Test public void prefersAShortFreeGapDetourButBuildsWhenTheDetourCostsMore() {
        Grid nearby=new Grid(1);
        nearby.set(3,0,0,LocalRoute.Cell.AIR);
        LocalRoute free=solve(nearby,new LocalRoute.Point(6,1,0),1);
        assertEquals("ready",free.status()); assertEquals(8,free.cost(),0.001);
        assertEquals(0,placements(free));
        Grid wide=new Grid(3);
        for(int z=-2;z<=2;z++) wide.set(3,0,z,LocalRoute.Cell.AIR);
        LocalRoute bridge=solve(wide,new LocalRoute.Point(6,1,0),1);
        assertEquals("ready",bridge.status()); assertEquals(10,bridge.cost(),0.001);
        assertEquals(1,placements(bridge));
    }
    @Test public void doesNotReplaceHazardousSupportOrWalkThroughAnAscentCeiling() {
        Grid hazard=new Grid(0); hazard.set(3,0,0,LocalRoute.Cell.BLOCKED);
        assertEquals("no_route",solve(hazard,new LocalRoute.Point(6,1,0),0).status());
        Grid ceiling=new Grid(0); ceiling.set(1,1,0,LocalRoute.Cell.SOLID);
        ceiling.set(0,3,0,LocalRoute.Cell.SOLID);
        assertEquals("no_route",solve(ceiling,new LocalRoute.Point(6,1,0),0).status());
    }
    @Test public void cannotInventMaterialsToCrossTheSameGap() {
        Grid grid=new Grid(0); for(int x=2;x<=4;x++) grid.set(x,0,0,LocalRoute.Cell.AIR);
        assertEquals("no_route",solve(grid,new LocalRoute.Point(6,1,0),0).status());
        assertNotEquals("ready",solve(grid,new LocalRoute.Point(6,1,0),2).status());
    }
    @Test public void constructsAnchoredStepsToReachAThreeBlockRise() {
        Grid grid=new Grid(0); for(int y=1;y<=3;y++) grid.set(4,y,0,LocalRoute.Cell.SOLID);
        LocalRoute route=solve(grid,new LocalRoute.Point(4,4,0),2);
        assertEquals("ready",route.status()); assertEquals(2,placements(route));
        assertEquals(13.5,route.cost(),0.001);
        assertEquals(new LocalRoute.Point(4,4,0),route.path().get(route.path().size()-1).target);
        assertNotEquals("ready",solve(grid,new LocalRoute.Point(4,4,0),1).status());
    }
    @Test public void refusesHazardsAndLowCeilingsAndCanYieldBetweenTicks() {
        Grid grid=new Grid(0); grid.set(3,1,0,LocalRoute.Cell.BLOCKED); grid.set(3,2,0,LocalRoute.Cell.BLOCKED);
        assertEquals("no_route",solve(grid,new LocalRoute.Point(6,1,0),0).status());
        LocalRoute route=new LocalRoute(new Grid(0),new LocalRoute.Point(0,1,0),new LocalRoute.Point(6,1,0),0,0);
        route.advance(1,100000000); assertEquals("searching",route.status()); assertEquals(1,route.expanded());
        while("searching".equals(route.status())) route.advance(1,100000000);
        assertEquals("ready",route.status()); assertEquals(6,route.cost(),0.001);
    }
}
