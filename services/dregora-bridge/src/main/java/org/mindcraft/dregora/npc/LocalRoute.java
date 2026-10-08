package org.mindcraft.dregora.npc;

import java.util.*;

/** Bounded incremental A*: position, remaining supplies and hypothetical supports are part of the state. */
final class LocalRoute {
    enum Cell { AIR, SOLID, BLOCKED }
    interface Terrain { Cell cell(Point point); }
    static final class Point implements Comparable<Point> {
        final int x, y, z;
        Point(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        Point add(int dx, int dy, int dz) { return new Point(x + dx, y + dy, z + dz); }
        @Override public boolean equals(Object other) { return other instanceof Point && x == ((Point) other).x && y == ((Point) other).y && z == ((Point) other).z; }
        @Override public int hashCode() { return Objects.hash(x, y, z); }
        @Override public int compareTo(Point other) { int c = Integer.compare(x, other.x); if (c == 0) c = Integer.compare(y, other.y); return c == 0 ? Integer.compare(z, other.z) : c; }
    }
    static final class Step {
        final Point target;
        final List<Point> placements;
        Step(Point target, List<Point> placements) { this.target = target; this.placements = placements; }
    }
    private static final class Node {
        final Point point;
        final SortedSet<Point> built;
        final Node parent;
        final Step step;
        final double cost, priority;
        Node(Point point, SortedSet<Point> built, Node parent, Step step, double cost, double priority) {
            this.point = point; this.built = built; this.parent = parent; this.step = step; this.cost = cost; this.priority = priority;
        }
    }
    private static final class Key {
        final Point point;
        final Set<Point> built;
        Key(Node node) { point = node.point; built = node.built; }
        @Override public int hashCode() { return 31 * point.hashCode() + built.hashCode(); }
        @Override public boolean equals(Object other) { return other instanceof Key && point.equals(((Key) other).point) && built.equals(((Key) other).built); }
    }
    private final Terrain terrain;
    private final Point start, goal;
    private final int supplies;
    private final double radius;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.priority));
    private final Map<Key, Double> best = new HashMap<>();
    private String status = "searching";
    private List<Step> path = Collections.emptyList();
    private double cost;
    private int expanded;
    LocalRoute(Terrain terrain, Point start, Point goal, int supplies, double radius) {
        this.terrain = terrain; this.start = start; this.goal = goal;
        this.supplies = Math.min(32, Math.max(0, supplies)); this.radius = radius;
        Node initial = new Node(start, new TreeSet<>(), null, null, 0, heuristic(start));
        open.add(initial); best.put(new Key(initial), 0.0);
    }
    String status() { return status; }
    List<Step> path() { return path; }
    double cost() { return cost; }
    int expanded() { return expanded; }
    private double heuristic(Point p) {
        // Subtract the maximum metric contribution of a spherical goal region.
        return Math.max(0,Math.abs(p.x-goal.x)+Math.abs(p.z-goal.z)+Math.abs(p.y-goal.y)*0.25-radius*Math.sqrt(2.0625));
    }
    private boolean arrived(Point p) { return Math.pow(p.x-goal.x,2)+Math.pow(p.y-goal.y,2)+Math.pow(p.z-goal.z,2) <= radius*radius; }
    private Cell cell(Point p, Set<Point> built) { return built.contains(p) ? Cell.SOLID : terrain.cell(p); }
    private boolean anchor(Point p, Set<Point> built) {
        for (int[] dir : DIRECTIONS) if (cell(p.add(dir[0],dir[1],dir[2]), built) == Cell.SOLID) return true;
        return false;
    }
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
    void advance(int nodeBudget, long nanosBudget) {
        long deadline = System.nanoTime() + nanosBudget;
        for (int i = 0; i < nodeBudget && "searching".equals(status); i++) {
            if (System.nanoTime() >= deadline) return;
            if (open.isEmpty()) { status = "no_route"; return; }
            Node node = open.poll();
            if (node.cost > best.getOrDefault(new Key(node), Double.POSITIVE_INFINITY)) continue;
            if (arrived(node.point)) {
                LinkedList<Step> steps = new LinkedList<>();
                for (Node current = node; current.parent != null; current = current.parent) steps.addFirst(current.step);
                path = steps; cost = node.cost; status = "ready"; return;
            }
            if (++expanded > 12000 || best.size() > 24000) { status = "search_budget_exceeded"; return; }
            for (int[] dir : Arrays.copyOf(DIRECTIONS, 4)) for (int dy = -1; dy <= 1; dy++) {
                Point target = node.point.add(dir[0],dy,dir[2]);
                if (Math.abs(target.x-start.x)>24 || Math.abs(target.z-start.z)>24 || Math.abs(target.y-start.y)>12) continue;
                SortedSet<Point> built = new TreeSet<>(node.built);
                List<Point> placements = new ArrayList<>();
                Point support = target.add(0,-1,0);
                if (cell(target,built)!=Cell.AIR || cell(target.add(0,1,0),built)!=Cell.AIR) continue;
                if (dy>0 && cell(node.point.add(0,2,0),built)!=Cell.AIR) continue;
                if (dy<0 && cell(target.add(0,2,0),built)!=Cell.AIR) continue;
                if (cell(support,built)!=Cell.SOLID) {
                    if (cell(support,built)!=Cell.AIR || built.size()>=supplies) continue;
                    if (!anchor(support,built)) {
                        Point foundation = support.add(0,-1,0);
                        if (dy!=1 || cell(foundation,built)!=Cell.AIR || !anchor(foundation,built) || built.size()+2>supplies) continue;
                        built.add(foundation); placements.add(foundation);
                    }
                    built.add(support); placements.add(support);
                }
                double nextCost = node.cost + 1 + (dy>0 ? 0.5 : dy<0 ? 0.25 : 0) + placements.size()*4;
                Node next = new Node(target,built,node,new Step(target,placements),nextCost,nextCost+heuristic(target));
                Key key = new Key(next);
                if (nextCost + 1e-9 >= best.getOrDefault(key,Double.POSITIVE_INFINITY)) continue;
                best.put(key,nextCost); open.add(next);
            }
        }
    }
}
