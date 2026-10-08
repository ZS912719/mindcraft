package org.mindcraft.dregora.npc;

import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

public final class NpcService {
    private static MinecraftServer activeServer;
    private static final Map<UUID, OwnerSession> sessions = new HashMap<>();
    private static final LinkedHashMap<String, JsonObject> results = new LinkedHashMap<>();
    private static final Map<String, JsonObject> requests = new HashMap<>();
    private NpcService() {}
    static void start(MinecraftServer server) {
        activeServer = server;
        sessions.clear();
        results.clear(); requests.clear();
    }
    static void stop() { activeServer = null; sessions.clear(); results.clear(); requests.clear(); }
    static void invalidate(net.minecraft.entity.player.EntityPlayer player) {
        if (player.world.isRemote || activeServer == null) return;
        sessions.remove(player.getUniqueID());
        for (net.minecraft.world.WorldServer world : activeServer.worlds)
            for (Entity entity : world.loadedEntityList)
                if (entity instanceof EntityTeammate && player.getUniqueID().equals(((EntityTeammate) entity).owner()))
                    ((EntityTeammate) entity).order("hold", null);
    }
    private static final class OwnerSession {
        final EntityPlayerMP player;
        final int dimension;
        final String id = UUID.randomUUID().toString();
        OwnerSession(EntityPlayerMP player) { this.player = player; dimension = player.dimension; }
    }
    private static String session(EntityPlayerMP player) {
        OwnerSession current = sessions.get(player.getUniqueID());
        if (current == null || current.player != player || current.dimension != player.dimension) {
            current = new OwnerSession(player); sessions.put(player.getUniqueID(), current);
        }
        return current.id;
    }

    public static EntityPlayerMP testOwner(MinecraftServer server, UUID owner) {
        if (!server.isCallingFromMinecraftThread()) throw new IllegalStateException("server_thread_required");
        if (server != activeServer || !server.isSinglePlayer()) throw new IllegalStateException("singleplayer_required");
        if (!"1".equals(System.getenv("MINDCRAFT_BRIDGE_TEST_MODE"))) throw new IllegalStateException("test_mode_required");
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(owner);
        if (player == null || !player.canUseCommand(2, "mindcraft_npc")) throw new IllegalStateException("cheats_required");
        return player;
    }

    public static JsonObject state(MinecraftServer server, UUID owner) {
        EntityPlayerMP player = testOwner(server, owner);
        JsonObject out = new JsonObject();
        out.addProperty("protocol", 1); out.addProperty("backend", "npc_prototype");
        out.addProperty("session", session(player)); out.addProperty("timestamp", System.currentTimeMillis());
        out.addProperty("dimension", player.dimension); out.addProperty("source", "integrated_server");
        out.addProperty("loadedOnly", true);
        JsonArray npcs = new JsonArray();
        for (Entity entity : player.world.loadedEntityList) if (entity instanceof EntityTeammate
            && owner.equals(((EntityTeammate) entity).owner())) npcs.add(snapshot((EntityTeammate) entity));
        out.add("npcs", npcs);
        JsonArray respawns = new JsonArray();
        net.minecraft.nbt.NBTTagList pending = NpcRespawns.state(server, owner);
        for (int i = 0; i < pending.tagCount(); i++) {
            net.minecraft.nbt.NBTTagCompound record = pending.getCompoundTagAt(i);
            JsonObject waiting = new JsonObject();
            waiting.addProperty("uuid", record.getUniqueId("UUID").toString());
            waiting.addProperty("remainingTicks", Math.max(0, record.getLong("Due") - server.getWorld(0).getTotalWorldTime()));
            waiting.addProperty("status", "respawn_pending"); respawns.add(waiting);
        }
        out.add("respawns", respawns);
        return out;
    }

    private static JsonObject snapshot(EntityTeammate npc) {
        JsonObject out = new JsonObject(), position = new JsonObject(), capabilities = new JsonObject();
        out.addProperty("uuid", npc.getUniqueID().toString()); out.addProperty("owner", npc.owner().toString());
        out.addProperty("alive", npc.isEntityAlive()); out.addProperty("health", npc.getHealth());
        out.addProperty("command", npc.command()); out.addProperty("movement", npc.movement());
        out.addProperty("summonTicks", npc.summonTicks());
        out.add("navigation", npc.navigationState()); out.addProperty("buildingSupplies", NpcBuilding.supplies(npc));
        position.addProperty("x", npc.posX); position.addProperty("y", npc.posY); position.addProperty("z", npc.posZ);
        out.add("position", position);
        for (String name : new String[] {"reskillable", "firstAid", "rlcombat", "baubles", "ranged", "useItem", "combat"}) {
            JsonObject capability = new JsonObject();
            capability.addProperty("status", "unsupported"); capability.addProperty("reason", "npc_integration_not_implemented");
            capabilities.add(name, capability);
        }
        out.add("capabilities", capabilities);
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < npc.backpack().getSlots(); i++) inventory.add(stack(npc.backpack().getStackInSlot(i)));
        out.add("inventory", inventory);
        JsonObject equipment = new JsonObject();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) equipment.add(slot.getName(), stack(npc.getItemStackFromSlot(slot)));
        out.add("equipment", equipment);
        return out;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject out = new JsonObject(); out.addProperty("empty", stack.isEmpty());
        if (!stack.isEmpty()) {
            out.addProperty("id", String.valueOf(stack.getItem().getRegistryName()));
            out.addProperty("count", stack.getCount()); out.addProperty("metadata", stack.getMetadata());
            out.addProperty("nbt", stack.hasTagCompound() ? stack.getTagCompound().toString() : null);
        }
        return out;
    }

    static EntityTeammate find(EntityPlayerMP player, UUID uuid) {
        for (Entity entity : player.world.loadedEntityList) if (entity instanceof EntityTeammate
            && uuid.equals(entity.getUniqueID()) && player.getUniqueID().equals(((EntityTeammate) entity).owner()))
            return (EntityTeammate) entity;
        throw new IllegalArgumentException("npc_not_loaded_or_not_owned");
    }

    public static void validate(JsonObject request) {
        Set<String> keys = new HashSet<>(Arrays.asList("id", "session", "uuid", "command", "destination"));
        for (Map.Entry<String, JsonElement> entry : request.entrySet()) if (!keys.contains(entry.getKey()))
            throw new IllegalArgumentException("unknown_argument");
        for (String key : new String[] {"id", "session", "uuid", "command"})
            if (!request.has(key) || !request.get(key).isJsonPrimitive() || !request.getAsJsonPrimitive(key).isString())
                throw new IllegalArgumentException("invalid_npc_command");
        if (!request.get("id").getAsString().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid_id");
        for (String key : new String[] {"session", "uuid"})
            if (!request.get(key).getAsString().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("invalid_uuid");
        String command = request.get("command").getAsString();
        if (!Arrays.asList("follow", "hold", "retreat", "summon", "navigate").contains(command)) throw new IllegalArgumentException("unsupported_npc_command");
        if ("retreat".equals(command) || "navigate".equals(command)) {
            if (!request.has("destination") || !request.get("destination").isJsonObject()) throw new IllegalArgumentException("destination_required");
            JsonObject target = request.getAsJsonObject("destination");
            if (target.size() != 3) throw new IllegalArgumentException("invalid_destination");
            for (String axis : new String[] {"x", "y", "z"}) {
                if (!target.has(axis) || !target.get(axis).isJsonPrimitive() || !target.getAsJsonPrimitive(axis).isNumber()
                    || !Double.isFinite(target.get(axis).getAsDouble()) || Math.abs(target.get(axis).getAsDouble()) > 30000000)
                    throw new IllegalArgumentException("invalid_destination");
            }
        } else if (request.has("destination")) throw new IllegalArgumentException("unexpected_destination");
    }

    public static JsonObject execute(MinecraftServer server, UUID owner, JsonObject request, long deadline) {
        EntityPlayerMP player = testOwner(server, owner);
        validate(request);
        String session = session(player);
        String id = request.get("id").getAsString();
        if (!request.get("session").getAsString().equals(session)) throw new IllegalArgumentException("stale_npc_session");
        EntityTeammate npc = find(player, UUID.fromString(request.get("uuid").getAsString()));
        if (results.containsKey(id)) {
            if (!request.equals(requests.get(id))) throw new IllegalArgumentException("command_id_conflict");
            return results.get(id);
        }
        if (System.currentTimeMillis() > deadline) throw new IllegalStateException("npc_command_expired");
        if (!npc.isEntityAlive() || !player.isEntityAlive() || player.isSpectator()) throw new IllegalStateException("actor_unavailable");
        String command = request.get("command").getAsString();
        Vec3d target = null;
        if (!"hold".equals(command) && !"summon".equals(command) && npc.getDistanceSq(player) > 32 * 32) throw new IllegalArgumentException("outside_local_range");
        if ("retreat".equals(command) || "navigate".equals(command)) {
            JsonObject destination = request.getAsJsonObject("destination");
            target = new Vec3d(destination.get("x").getAsDouble(), destination.get("y").getAsDouble(), destination.get("z").getAsDouble());
            if (npc.getPositionVector().squareDistanceTo(target) > 32 * 32 || !safeDestination(npc, target))
                throw new IllegalArgumentException("destination_unsafe_or_unloaded");
        }
        if ("summon".equals(command)) NpcSummoning.request(player, npc);
        else npc.order(command, target);
        JsonObject result = new JsonObject();
        result.addProperty("id", id); result.addProperty("uuid", npc.getUniqueID().toString()); result.addProperty("session", session);
        result.addProperty("status", "completed"); result.addProperty("reason", "command_set");
        result.addProperty("command", command); result.addProperty("effectVerified", false);
        // Command acceptance is not evidence that navigation reached its destination.
        if (results.size() >= 256) { String oldest = results.keySet().iterator().next(); results.remove(oldest); requests.remove(oldest); }
        requests.put(id, new JsonParser().parse(request.toString()).getAsJsonObject()); results.put(id, result);
        return result;
    }

    static boolean safeDestination(EntityTeammate npc, Vec3d target) {
        BlockPos pos = new BlockPos(target);
        if (pos.getY() < 1 || pos.getY() >= npc.world.getHeight() - 1 || !npc.world.isAreaLoaded(pos.add(-1, -1, -1), pos.add(1, 2, 1))
            || !npc.world.getWorldBorder().contains(pos)) return false;
        for (BlockPos check : new BlockPos[] {pos.down(), pos, pos.up()}) {
            net.minecraft.block.state.IBlockState state = npc.world.getBlockState(check);
            if (state.getMaterial().isLiquid() || state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.CACTUS
                || state.getBlock() == Blocks.MAGMA) return false;
        }
        return npc.world.getBlockState(pos.down()).isTopSolid()
            && npc.world.getCollisionBoxes(npc, npc.getEntityBoundingBox().offset(target.x - npc.posX, target.y - npc.posY, target.z - npc.posZ)).isEmpty();
    }
}
