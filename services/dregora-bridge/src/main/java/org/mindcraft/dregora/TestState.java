package org.mindcraft.dregora;

import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import java.util.UUID;

final class TestState {
    private TestState() {}

    static JsonObject read(IntegratedServer server, UUID playerId) {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null || !player.canUseCommand(2, "summon")) throw new IllegalStateException("cheats_required");
        JsonObject out = new JsonObject();
        out.addProperty("timestamp", System.currentTimeMillis());
        out.addProperty("source", "integrated_server");
        out.addProperty("health", player.getHealth());
        out.addProperty("armor", player.getTotalArmorValue());
        JsonObject mods = ModState.read(player);
        for (java.util.Map.Entry<String, JsonElement> entry : mods.entrySet())
            entry.getValue().getAsJsonObject().addProperty("source", "integrated_server");
        out.add("mods", mods);
        JsonArray entities = new JsonArray();
        for (Entity entity : player.getServerWorld().loadedEntityList) {
            if (!entity.getTags().contains("MindcraftFixture")) continue;
            JsonObject data = new JsonObject();
            data.addProperty("entityId", entity.getEntityId());
            data.addProperty("uuid", entity.getUniqueID().toString());
            data.addProperty("name", entity.getName());
            data.addProperty("alive", entity.isEntityAlive());
            data.addProperty("x", entity.posX); data.addProperty("y", entity.posY); data.addProperty("z", entity.posZ);
            if (entity instanceof EntityLivingBase) data.addProperty("health", ((EntityLivingBase) entity).getHealth());
            entities.add(data);
            if (entities.size() >= 128) break;
        }
        out.add("entities", entities);
        return out;
    }
}
