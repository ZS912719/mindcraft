package org.mindcraft.dregora;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.Loader;
import java.lang.reflect.Field;

final class ModState {
    private ModState() {}

    static JsonObject read(EntityPlayer player) {
        JsonObject result = new JsonObject();
        result.add("thirst", readOptional("simpledifficulty", () -> {
            Object value = Class.forName("com.charles445.simpledifficulty.api.SDCapabilities")
                .getMethod("getThirstData", EntityPlayer.class).invoke(null, player);
            JsonObject data = new JsonObject();
            data.addProperty("level", number(value, "getThirstLevel"));
            data.addProperty("saturation", number(value, "getThirstSaturation"));
            return data;
        }));
        result.add("temperature", readOptional("simpledifficulty", () -> {
            Object value = Class.forName("com.charles445.simpledifficulty.api.SDCapabilities")
                .getMethod("getTemperatureData", EntityPlayer.class).invoke(null, player);
            JsonObject data = new JsonObject();
            data.addProperty("level", number(value, "getTemperatureLevel"));
            data.addProperty("category", value.getClass().getMethod("getTemperatureEnum").invoke(value).toString());
            return data;
        }));
        result.add("body", readOptional("firstaid", () -> {
            Capability<?> capability = (Capability<?>) Class.forName("ichttt.mods.firstaid.api.CapabilityExtendedHealthSystem")
                .getField("INSTANCE").get(null);
            Object model = player.getCapability(capability, null);
            JsonObject data = new JsonObject();
            for (String name : new String[] {"HEAD", "BODY", "LEFT_ARM", "RIGHT_ARM", "LEFT_LEG", "RIGHT_LEG", "LEFT_FOOT", "RIGHT_FOOT"}) {
                Object part = model.getClass().getField(name).get(model);
                JsonObject limb = new JsonObject();
                limb.addProperty("health", (Number) part.getClass().getField("currentHealth").get(part));
                limb.addProperty("maxHealth", number(part, "getMaxHealth"));
                limb.addProperty("critical", part.getClass().getField("canCauseDeath").getBoolean(part));
                data.add(name.toLowerCase(java.util.Locale.ROOT), limb);
            }
            return data;
        }));
        result.add("skills", readOptional("reskillable", () -> {
            Object playerData = Class.forName("codersafterdark.reskillable.api.data.PlayerDataHandler")
                .getMethod("get", EntityPlayer.class).invoke(null, player);
            Iterable<?> skills = (Iterable<?>) playerData.getClass().getMethod("getAllSkillInfo").invoke(playerData);
            JsonObject data = new JsonObject();
            for (Object info : skills) {
                Field field = info.getClass().getField("skill");
                Object skill = field.get(info);
                String id = skill.getClass().getMethod("getRegistryName").invoke(skill).toString();
                data.addProperty(id, number(info, "getLevel"));
            }
            return data;
        }));
        return result;
    }

    private static Number number(Object target, String method) throws Exception {
        return (Number) target.getClass().getMethod(method).invoke(target);
    }

    private interface Reader { JsonObject read() throws Exception; }

    private static JsonObject readOptional(String mod, Reader reader) {
        JsonObject result = new JsonObject();
        result.addProperty("source", "client_synced");
        if (!Loader.isModLoaded(mod)) {
            result.addProperty("status", "unavailable");
            result.addProperty("reason", "mod_not_loaded");
            return result;
        }
        try {
            result.add("value", reader.read());
            result.addProperty("status", "available");
        } catch (Exception | LinkageError error) {
            result.addProperty("status", "unknown");
            result.addProperty("reason", "capability_read_failed");
        }
        return result;
    }
}
