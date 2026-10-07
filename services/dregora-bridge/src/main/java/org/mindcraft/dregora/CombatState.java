package org.mindcraft.dregora;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.Loader;

final class CombatState {
    private CombatState() {}

    static double reach(EntityPlayer player, EnumHand hand) throws ReflectiveOperationException {
        if (Loader.isModLoaded("reachfix")) {
            return ((Number) Class.forName("meldexun.reachfix.util.ReachFixUtil")
                .getMethod("getEntityReach", EntityPlayer.class, EnumHand.class).invoke(null, player, hand)).doubleValue();
        }
        return player.capabilities.isCreativeMode ? 6 : 3;
    }

    static RayTraceResult pointed(Minecraft mc) throws ReflectiveOperationException {
        if (!Loader.isModLoaded("bettercombatmod")) return mc.objectMouseOver;
        Class<?> config = Class.forName("bettercombat.mod.util.ConfigurationHandler");
        Object server = config.getField("server").get(null);
        boolean passable = server.getClass().getField("swingThroughPassableBlocks").getBoolean(server);
        Class<?> util = Class.forName("bettercombat.mod.util.ReachFixUtil");
        Class<?>[] signature = {Entity.class, EntityPlayer.class, EnumHand.class, World.class, float.class};
        Object[] args = {mc.getRenderViewEntity(), mc.player, EnumHand.MAIN_HAND, mc.world, 1F};
        RayTraceResult hit = passable ? (RayTraceResult) util.getMethod("pointedObjectIgnorePassable", signature).invoke(null, args) : null;
        if (hit == null || hit.entityHit == null || hit.entityHit == mc.player)
            hit = (RayTraceResult) util.getMethod("pointedObject", signature).invoke(null, args);
        return hit;
    }

    static String attack(Minecraft mc, Entity target) throws ReflectiveOperationException {
        if (mc.player.isHandActive()) return "item_use_active";
        RayTraceResult hit = pointed(mc);
        if (hit == null || hit.entityHit != target || hit.hitVec == null) return "target_not_aimed_or_out_of_reach";
        if (mc.player.getPositionEyes(1).distanceTo(hit.hitVec) > reach(mc.player, EnumHand.MAIN_HAND))
            return "target_unreachable";
        if (mc.player.getCooledAttackStrength(0) < 1) return "attack_cooldown";
        if (Loader.isModLoaded("bettercombatmod")) {
            // Use the mod's packet, cooldown and animation path instead of a vanilla attack packet.
            MouseEvent event = new MouseEvent();
            Class.forName("bettercombat.mod.client.handler.EventHandlersClient")
                .getMethod("onMouseLeftClick", MouseEvent.class).invoke(null, event);
            return event.isCanceled() ? "dispatched" : "weapon_requires_special_action";
        }
        mc.playerController.attackEntity(mc.player, target);
        mc.player.swingArm(EnumHand.MAIN_HAND);
        return "dispatched";
    }

    static JsonObject read(Minecraft mc) {
        JsonObject out = new JsonObject();
        out.addProperty("attackStrength", mc.player.getCooledAttackStrength(0));
        out.addProperty("activeHand", mc.player.isHandActive() ? mc.player.getActiveHand().name() : null);
        out.addProperty("useTicks", mc.player.getItemInUseMaxCount());
        out.addProperty("backend", Loader.isModLoaded("bettercombatmod") ? "rlcombat" : "vanilla");
        out.addProperty("attackDamage", mc.player.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue());
        out.addProperty("attackSpeed", mc.player.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_SPEED).getAttributeValue());
        out.addProperty("armorToughness", mc.player.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ARMOR_TOUGHNESS).getAttributeValue());
        try {
            double main = reach(mc.player, EnumHand.MAIN_HAND), off = reach(mc.player, EnumHand.OFF_HAND);
            if (!Double.isFinite(main) || main <= 0 || !Double.isFinite(off) || off <= 0) throw new IllegalStateException();
            out.addProperty("status", "available");
            out.addProperty("mainReach", main);
            out.addProperty("offReach", off);
            RayTraceResult hit = pointed(mc);
            if (hit != null && hit.entityHit != null) {
                out.addProperty("aimedEntityId", hit.entityHit.getEntityId());
                out.addProperty("aimedEntityUuid", hit.entityHit.getUniqueID().toString());
            }
        } catch (Exception exception) {
            // Unknown compatibility must not be presented as a measured reach value.
            out.addProperty("status", "unknown");
        }
        return out;
    }
}
