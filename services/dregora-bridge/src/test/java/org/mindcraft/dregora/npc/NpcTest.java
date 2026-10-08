package org.mindcraft.dregora.npc;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NpcTest {
    private JsonObject request(String command) {
        JsonObject request = new JsonObject();
        request.addProperty("id", "npc-test");
        request.addProperty("session", "00000000-0000-4000-8000-000000000001");
        request.addProperty("uuid", "00000000-0000-4000-8000-000000000002");
        request.addProperty("command", command);
        return request;
    }
    private void reject(JsonObject request) {
        try { NpcService.validate(request); fail("Invalid NPC commands must be rejected"); }
        catch (IllegalArgumentException expected) {}
    }
    @Test public void acceptsOnlyBoundedStructuredMovement() {
        NpcService.validate(request("follow")); NpcService.validate(request("hold"));
        JsonObject retreat = request("retreat"), target = new JsonObject();
        target.addProperty("x", 1); target.addProperty("y", 64); target.addProperty("z", 2);
        retreat.add("destination", target); NpcService.validate(retreat);
        retreat.addProperty("command", "navigate"); NpcService.validate(retreat);
        target.addProperty("y", Double.NaN); reject(retreat);
        target.addProperty("y", "64"); reject(retreat);
        target.addProperty("y", 64); target.addProperty("script", "arbitrary"); reject(retreat);
    }
    @Test public void rejectsPlayerActionsAndAmbiguousSubjects() {
        reject(request("attack")); reject(request("use_item")); reject(request("retreat"));
        JsonObject invalid = request("follow"); invalid.addProperty("uuid", "1-1-1-1-1"); reject(invalid);
        invalid = request("hold"); invalid.addProperty("owner", "other-player"); reject(invalid);
        invalid = request("hold"); invalid.add("destination", new JsonObject()); reject(invalid);
    }
    @Test public void movementStopsWhenOwnerIsUnavailableOrGoalExpires() {
        assertEquals("owner_unavailable", MovementPolicy.decide("follow", false, 100, 0));
        assertEquals("owner_unavailable", MovementPolicy.decide("retreat", false, 100, 200));
        assertEquals("retreat_expired", MovementPolicy.decide("retreat", true, 100, 0));
        assertEquals("outside_local_range", MovementPolicy.decide("follow", true, 1025, 0));
        assertEquals("outside_local_range", MovementPolicy.decide("retreat", true, Double.NaN, 20));
    }
    @Test public void followAndRetreatUseDistinctArrivalDistances() {
        assertEquals("arrived", MovementPolicy.decide("follow", true, 9, 0));
        assertEquals("moving", MovementPolicy.decide("follow", true, 10, 0));
        assertEquals("arrived", MovementPolicy.decide("retreat", true, 4, 100));
        assertEquals("moving", MovementPolicy.decide("retreat", true, 5, 100));
        assertEquals("holding", MovementPolicy.decide("hold", true, 100, 0));
        assertEquals("arrived", MovementPolicy.decide("navigate", true, 1, 1200));
        assertEquals("moving", MovementPolicy.decide("navigate", true, 2, 1200));
        assertEquals("navigation_expired", MovementPolicy.decide("navigate", true, 100, 0));
    }
    @Test public void followRecoveryHasBothNearAndFarBoundaries() {
        assertFalse(MovementPolicy.recoverFollow("follow", true, 143.99, 0, false));
        assertTrue(MovementPolicy.recoverFollow("follow", true, 144, 0, false));
        assertTrue(MovementPolicy.recoverFollow("follow", true, 1024, 0, false));
        assertFalse(MovementPolicy.recoverFollow("follow", true, 1024.01, 0, false));
        assertEquals("outside_local_range", MovementPolicy.decide("follow", true, 1024.01, 0));
        assertFalse(MovementPolicy.recoverFollow("follow", true, Double.NaN, 0, false));
        assertFalse(MovementPolicy.recoverFollow("follow", true, Double.POSITIVE_INFINITY, 0, false));
    }
    @Test public void followRecoveryRespectsOrdersOwnerAndRetryDelay() {
        for (String command : new String[] {"hold", "retreat", "navigate", "summon"})
            assertFalse(MovementPolicy.recoverFollow(command, true, 400, 0, false));
        assertFalse(MovementPolicy.recoverFollow("follow", false, 400, 0, false));
        assertFalse(MovementPolicy.recoverFollow("follow", true, 400, 1, false));
        assertFalse(MovementPolicy.recoverFollow("follow", true, 400, 0, true));
        assertTrue(MovementPolicy.recoverFollow("follow", true, 400, 0, false));
        assertEquals(20, MovementPolicy.FOLLOW_RECOVERY_RETRY_TICKS);
    }
}
