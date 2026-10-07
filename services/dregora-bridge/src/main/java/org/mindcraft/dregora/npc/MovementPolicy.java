package org.mindcraft.dregora.npc;

final class MovementPolicy {
    private MovementPolicy() {}
    static String decide(String command, boolean ownerReady, double distanceSquared, int remainingTicks) {
        if (!ownerReady) return "owner_unavailable";
        if (!Double.isFinite(distanceSquared) || distanceSquared > 32 * 32) return "outside_local_range";
        if ("hold".equals(command)) return "holding";
        if ("retreat".equals(command) && remainingTicks <= 0) return "retreat_expired";
        if (distanceSquared <= ("follow".equals(command) ? 3 * 3 : 2 * 2)) return "arrived";
        return "moving";
    }
}
