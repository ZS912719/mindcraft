package org.mindcraft.dregora;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservationTest {
    private JsonObject state(String equipment, int window, float health) {
        JsonObject state = new JsonParser().parse("{\"source\":\"integrated_server\",\"inventory\":[],\"effects\":{},\"blocks\":[\"stone\"],\"equipment\":{\"head\":\""
            + equipment + "\",\"mainhand\":\"unchanged\",\"offhand\":\"unchanged\"},\"windowId\":" + window + ",\"targetHealth\":" + health + "}").getAsJsonObject();
        return state;
    }

    @Test public void equipmentWearDoesNotVerifyDamage() {
        assertFalse(ActionObservation.compare(state("worn", 0, 20), state("damaged", 0, 20), "attack").get("effectVerified").getAsBoolean());
        assertTrue(ActionObservation.compare(state("worn", 0, 20), state("damaged", 0, 16), "attack").get("effectVerified").getAsBoolean());
    }

    @Test public void requiresTheRequestedArmorRatherThanAnyEquipmentChange() {
        JsonObject before = state("empty", 0, 20);
        before.addProperty("expectedArmorSlot", "head"); before.addProperty("expectedArmor", "diamond");
        assertFalse(ActionObservation.compare(before, state("iron", 0, 20), "equip_armor").get("effectVerified").getAsBoolean());
        assertTrue(ActionObservation.compare(before, state("diamond", 0, 20), "equip_armor").get("effectVerified").getAsBoolean());
    }

    @Test public void interactionRequiresBlockOrContainerEvidence() {
        assertFalse(ActionObservation.compare(state("empty", 0, 20), state("changed", 0, 20), "interact_block").get("effectVerified").getAsBoolean());
        assertTrue(ActionObservation.compare(state("empty", 0, 20), state("empty", 1, 20), "interact_block").get("effectVerified").getAsBoolean());
    }

    @Test public void unrelatedInventoryPickupDoesNotVerifyItemUse() {
        JsonObject before = state("empty", 0, 20), after = state("empty", 0, 20);
        after.getAsJsonArray("inventory").add("unrelated_pickup");
        assertFalse(ActionObservation.compare(before, after, "use_item").get("effectVerified").getAsBoolean());
        after.getAsJsonObject("equipment").addProperty("mainhand", "consumed_item");
        assertTrue(ActionObservation.compare(before, after, "use_item").get("effectVerified").getAsBoolean());
    }
}
