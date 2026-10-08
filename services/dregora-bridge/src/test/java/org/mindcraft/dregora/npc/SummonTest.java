package org.mindcraft.dregora.npc;

import com.google.gson.JsonObject;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class SummonTest {
    @Test public void landingBoundsAreCylindricalAndIncludeVerticalBoundary() {
        assertTrue(SummonPolicy.withinRange(3, 3, 4));
        assertTrue(SummonPolicy.withinRange(0, -3, 0));
        assertFalse(SummonPolicy.withinRange(5, 0, 5));
        assertFalse(SummonPolicy.withinRange(0, 3.01, 0));
        assertFalse(SummonPolicy.withinRange(Double.NaN, 0, 0));
        assertFalse(SummonPolicy.withinRange(0, Double.POSITIVE_INFINITY, 0));
    }
    @Test public void rescueTriggersBeforeDrowningAndForLethalEnvironment() {
        assertTrue(SummonPolicy.emergency(true, false, 300, false, 20, false));
        assertTrue(SummonPolicy.emergency(false, true, 40, false, 20, false));
        assertFalse(SummonPolicy.emergency(false, true, 41, false, 20, false));
        assertFalse(SummonPolicy.emergency(false, false, 0, false, 20, false));
        assertTrue(SummonPolicy.emergency(false, false, 300, true, 6, false));
        assertTrue(SummonPolicy.emergency(false, false, 300, false, 20, true));
        assertEquals(60, SummonPolicy.SUMMON_TICKS);
        assertEquals(600, SummonPolicy.RESPAWN_TICKS);
    }
    @Test public void respawnIdentityAndDeadlineSurviveSaveWithoutItemCopies() {
        UUID uuid = UUID.randomUUID(), owner = UUID.randomUUID();
        NBTTagCompound record = new NBTTagCompound();
        record.setUniqueId("UUID", uuid); record.setUniqueId("Owner", owner);
        record.setLong("Due", 12345); record.setString("Name", "Teammate");
        NBTTagList entries = new NBTTagList(); entries.appendTag(record);
        NBTTagCompound saved = new NBTTagCompound(); saved.setTag("Pending", entries);
        NpcRespawns queue = new NpcRespawns(); queue.readFromNBT(saved);
        NBTTagCompound restored = queue.writeToNBT(new NBTTagCompound()).getTagList("Pending", 10).getCompoundTagAt(0);
        assertEquals(uuid, restored.getUniqueId("UUID")); assertEquals(owner, restored.getUniqueId("Owner"));
        assertEquals(12345, restored.getLong("Due"));
        assertFalse(restored.hasKey("MindcraftBackpack")); assertFalse(restored.hasKey("ArmorItems"));
    }
    @Test public void summonAcceptsNoClientSelectedDestination() {
        JsonObject request = new JsonObject(); request.addProperty("id", "summon-test");
        request.addProperty("uuid", UUID.randomUUID().toString()); request.addProperty("session", UUID.randomUUID().toString());
        request.addProperty("command", "summon"); NpcService.validate(request);
        request.add("destination", new JsonObject());
        try { NpcService.validate(request); fail("Summon must choose its own safe destination"); }
        catch (IllegalArgumentException expected) { assertEquals("unexpected_destination", expected.getMessage()); }
    }
}
