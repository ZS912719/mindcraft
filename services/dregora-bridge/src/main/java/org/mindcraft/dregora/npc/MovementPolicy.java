package org.mindcraft.dregora.npc;

final class MovementPolicy {
    static final int FOLLOW_RECOVERY_DISTANCE = 12;
    static final int LOCAL_RANGE = 32;
    static final int FOLLOW_RECOVERY_RETRY_TICKS = 20;
    private MovementPolicy() {}
    static boolean recoverFollow(String command, boolean ownerReady, double distanceSquared,
                                 int retryTicks, boolean restrained) {
        return "follow".equals(command) && ownerReady && !restrained && retryTicks == 0
            && Double.isFinite(distanceSquared)
            && distanceSquared >= FOLLOW_RECOVERY_DISTANCE * FOLLOW_RECOVERY_DISTANCE
            && distanceSquared <= LOCAL_RANGE * LOCAL_RANGE;
    }
    static String decide(String command, boolean ownerReady, double distanceSquared, int remainingTicks) {
        if (!ownerReady) return "owner_unavailable";
        if (!Double.isFinite(distanceSquared) || distanceSquared > LOCAL_RANGE * LOCAL_RANGE) return "outside_local_range";
        if ("hold".equals(command)) return "holding";
        if ("retreat".equals(command) && remainingTicks <= 0) return "retreat_expired";
        if ("navigate".equals(command) && remainingTicks <= 0) return "navigation_expired";
        if (distanceSquared <= ("follow".equals(command) ? 9 : "navigate".equals(command) ? 1 : 4)) return "arrived";
        return "moving";
    }
}
