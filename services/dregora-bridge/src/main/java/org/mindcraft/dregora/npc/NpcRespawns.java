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
            Entity existing = null;
            for (WorldServer world : server.worlds) {
                Entity entity = world.getEntityFromUuid(entry.getKey());
                if (entity != null) { existing = entity; break; }
            }
            if (existing != null) {
                if (existing instanceof EntityTeammate && existing.isEntityAlive()
                    && record.getUniqueId("Owner").equals(((EntityTeammate) existing).owner())) {
                    iterator.remove(); data.markDirty();
                }
                continue;
            }
            EntityTeammate npc = new EntityTeammate(owner.world);
            npc.setUniqueId(entry.getKey()); npc.setOwner(owner.getUniqueID());
            npc.setCustomNameTag(record.getString("Name"));
            Vec3d target = NpcSummoning.landing(npc, owner);
            if (target == null) continue;
            npc.setPosition(target.x, target.y, target.z);
            if (!owner.world.spawnEntity(npc)) continue;
            npc.summonFailed("respawned"); NpcSummoning.particles(npc);
            iterator.remove(); data.markDirty();
        }
    }
    static NBTTagList state(MinecraftServer server, UUID owner) {
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound record : data(server).pending.values())
            if (owner.equals(record.getUniqueId("Owner"))) list.appendTag(record.copy());
        return list;
    }
    @Override public void readFromNBT(NBTTagCompound tag) {
        pending.clear(); NBTTagList list = tag.getTagList("Pending", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound record = list.getCompoundTagAt(i);
            if (record.hasUniqueId("UUID") && record.hasUniqueId("Owner")) pending.put(record.getUniqueId("UUID"), record.copy());
        }
    }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound record : pending.values()) list.appendTag(record.copy());
        tag.setTag("Pending", list); return tag;
    }
}
