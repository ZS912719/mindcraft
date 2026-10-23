package org.mindcraft.dregora.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldSavedData;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persist only identity and timing; death drops must never be duplicated. */
public final class NpcRespawns extends WorldSavedData {
    private static final String KEY = "mindcraft_npc_respawns";
    private final Map<UUID, NBTTagCompound> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> nextAttempt = new LinkedHashMap<>();
    public NpcRespawns() { super(KEY); }
    public NpcRespawns(String name) { super(name); }
    private static NpcRespawns data(MinecraftServer server) {
        WorldServer world = server.getWorld(0);
        NpcRespawns data = (NpcRespawns) world.getMapStorage().getOrLoadData(NpcRespawns.class, KEY);
        if (data == null) { data = new NpcRespawns(); world.getMapStorage().setData(KEY, data); }
        return data;
    }
    static void schedule(EntityTeammate npc) {
        if (npc.owner() == null) return;
        MinecraftServer server = npc.getServer();
        NpcRespawns data = data(server);
        if (data.pending.containsKey(npc.getUniqueID())) return;
        NBTTagCompound record = new NBTTagCompound();
        record.setUniqueId("UUID", npc.getUniqueID()); record.setUniqueId("Owner", npc.owner());
        record.setString("Name", npc.getCustomNameTag());
        record.setLong("Due", server.getWorld(0).getTotalWorldTime() + SummonPolicy.RESPAWN_TICKS);
        data.pending.put(npc.getUniqueID(), record); data.markDirty();
    }
    static void tick(MinecraftServer server) {
        long time = server.getWorld(0).getTotalWorldTime();
        NpcRespawns data = data(server);
        Iterator<Map.Entry<UUID, NBTTagCompound>> iterator = data.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, NBTTagCompound> entry = iterator.next();
            NBTTagCompound record = entry.getValue();
            if (time < record.getLong("Due")) continue;
            EntityPlayerMP owner = server.getPlayerList().getPlayerByUUID(record.getUniqueId("Owner"));
            if (owner == null || !owner.isEntityAlive() || owner.isSpectator()) continue;
            if (time < data.nextAttempt.getOrDefault(entry.getKey(), 0L)) continue;
            data.nextAttempt.put(entry.getKey(), time + 20);
            Entity existing = null;
            for (WorldServer world : server.worlds) {
                Entity entity = world.getEntityFromUuid(entry.getKey());
                if (entity != null) { existing = entity; break; }
            }
            if (existing != null) {
                if (existing instanceof EntityTeammate && existing.isEntityAlive()
                    && record.getUniqueId("Owner").equals(((EntityTeammate) existing).owner())) {
                    iterator.remove(); data.nextAttempt.remove(entry.getKey()); data.markDirty();
                }
                continue;
            }
            EntityTeammate npc = new EntityTeammate(owner.world);
            npc.setUniqueId(entry.getKey()); npc.setOwner(owner.getUniqueID());
            npc.setCustomNameTag(record.getString("Name"));
            // Bound expensive landing searches to one pending NPC per server tick.
            Vec3d target = NpcSummoning.landing(npc, owner);
            if (target == null) return;
            npc.setPosition(target.x, target.y, target.z);
            if (!owner.world.spawnEntity(npc)) return;
            npc.summonFailed("respawned"); NpcSummoning.particles(npc);
            iterator.remove(); data.nextAttempt.remove(entry.getKey()); data.markDirty();
            return;
        }
    }
    static NBTTagList state(MinecraftServer server, UUID owner) {
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound record : data(server).pending.values())
            if (owner.equals(record.getUniqueId("Owner"))) list.appendTag(record.copy());
        return list;
    }
    @Override public void readFromNBT(NBTTagCompound tag) {
        pending.clear(); nextAttempt.clear(); NBTTagList list = tag.getTagList("Pending", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound record = list.getCompoundTagAt(i);
            if (record.hasUniqueId("UUID") && record.hasUniqueId("Owner") && record.hasKey("Due", 99)
                && record.getLong("Due") >= 0) {
                NBTTagCompound clean = new NBTTagCompound();
                clean.setUniqueId("UUID", record.getUniqueId("UUID")); clean.setUniqueId("Owner", record.getUniqueId("Owner"));
                clean.setLong("Due", record.getLong("Due")); clean.setString("Name", record.getString("Name"));
                pending.put(clean.getUniqueId("UUID"), clean);
            }
        }
    }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound record : pending.values()) list.appendTag(record.copy());
        tag.setTag("Pending", list); return tag;
    }
}
