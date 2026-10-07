package org.mindcraft.dregora;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.fail;

public class ActionValidationTest {
    private JsonObject action(String type, String args) {
        return new JsonParser().parse("{\"id\":\"test\",\"session\":\"00000000-0000-4000-8000-000000000001\",\"type\":\""
            + type + "\",\"args\":" + args + "}").getAsJsonObject();
    }

    private void rejected(String type, String args) {
        try {
            DregoraBridge.validate(action(type, args));
            fail("Expected an invalid action to be rejected");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // Invalid requests must not reach the game thread.
        }
    }

    @Test public void acceptsBoundedMovementAndStop() {
        DregoraBridge.validate(action("move", "{\"ticks\":20,\"forward\":true}"));
        DregoraBridge.validate(action("stop", "{}"));
        DregoraBridge.validate(action("look", "{\"yaw\":180,\"pitch\":-90}"));
    }

    @Test public void rejectsMalformedControls() {
        rejected("move", "{\"ticks\":21}");
        rejected("move", "{\"ticks\":1.5}");
        rejected("move", "{\"ticks\":5,\"forward\":\"true\"}");
        rejected("look", "{\"yaw\":0,\"pitch\":91}");
        rejected("select_slot", "{\"slot\":9}");
        rejected("use_item", "{\"hand\":\"main\",\"ticks\":101}");
        rejected("attack", "{\"entityId\":5,\"uuid\":\"bad\"}");
    }

    @Test public void rejectsUnknownFieldsAndCommands() {
        rejected("stop", "{\"script\":\"anything\"}");
        rejected("execute_code", "{}");
        JsonObject action = action("stop", "{}");
        action.addProperty("deadline", Long.MAX_VALUE);
        try { DregoraBridge.validate(action); fail("Unexpected field accepted"); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void limitsTestCommandsToSingleGameCommands() {
        DregoraBridge.validate(action("test_command", "{\"command\":\"give DregoraTest minecraft:bow\"}"));
        rejected("test_command", "{\"command\":\"op DregoraTest\"}");
        rejected("test_command", "{\"command\":\"give DregoraTest minecraft:bow\\nkill @e\"}");
        rejected("test_command", "{\"command\":3}");
        rejected("test_command", "{\"command\":\"say hello\"}");
    }
}
