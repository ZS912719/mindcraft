package org.mindcraft.dregora.npc;

final class SummonPolicy {
    static final int SUMMON_TICKS = 60;
    static final int RESPAWN_TICKS = 600;
    private SummonPolicy() {}
    static boolean withinRange(double dx, double dy, double dz) {
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
            && dx * dx + dz * dz <= 25 && Math.abs(dy) <= 3;
    }
    static boolean emergency(boolean lava, boolean submerged, int air, boolean burning,
                             float health, boolean lethalEnvironmentalDamage) {
        return lava || (submerged && air <= 40) || (burning && health <= 6)
            || lethalEnvironmentalDamage;
    }
}
