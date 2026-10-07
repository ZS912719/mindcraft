package org.mindcraft.dregora;

import com.google.gson.*;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import java.util.Map;

final class ItemProfile {
    private ItemProfile() {}

    private static boolean inherits(Item item, String name) {
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(name)) return true;
        return false;
    }

    static JsonObject read(ItemStack stack) {
        Item item = stack.getItem();
        JsonObject out = new JsonObject();
        out.addProperty("class", item.getClass().getName());
        out.addProperty("useAction", stack.getItemUseAction().name());
        out.addProperty("maxUseTicks", stack.getMaxItemUseDuration());
        out.addProperty("kind", inherits(item, "com.oblivioussp.spartanweaponry.item.ItemCrossbow") ? "crossbow"
            : inherits(item, "com.oblivioussp.spartanweaponry.item.ItemThrowingWeapon") ? "throwable"
            : inherits(item, "com.mujmajnkraft.bettersurvival.items.ItemNunchaku") ? "continuous_melee"
            : item instanceof ItemBow ? "bow" : item instanceof ItemArrow ? "ammunition"
            : item instanceof ItemArmor ? "armor" : item instanceof ItemPotion ? "potion"
            : item instanceof ItemShield ? "shield" : item instanceof ItemSword || item instanceof ItemTool ? "melee"
            : "unknown");
        JsonArray attributes = new JsonArray();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            for (Map.Entry<String, AttributeModifier> entry : stack.getAttributeModifiers(slot).entries()) {
                JsonObject attribute = new JsonObject();
                attribute.addProperty("slot", slot.getName());
                attribute.addProperty("attribute", entry.getKey());
                attribute.addProperty("amount", entry.getValue().getAmount());
                attribute.addProperty("operation", entry.getValue().getOperation());
                attributes.add(attribute);
            }
        }
        out.add("attributes", attributes);
        // Mod-specific values describe charge and launch speed, not an effective hit range.
        for (String method : new String[] {"getDrawTicks", "getMaxArrowSpeed", "getBoltSpeed", "getMaxAmmoBase"}) {
            try {
                Object value = item.getClass().getMethod(method).invoke(item);
                if (value instanceof Number) out.addProperty(method, (Number) value);
            } catch (ReflectiveOperationException ignored) {}
        }
        try {
            Object value = item.getClass().getMethod("getMaxChargeTicks", ItemStack.class).invoke(item, stack);
            if (value instanceof Number) out.addProperty("getMaxChargeTicks", (Number) value);
        } catch (ReflectiveOperationException ignored) {}
        try {
            Object value = item.getClass().getMethod("isLoaded", ItemStack.class).invoke(item, stack);
            if (value instanceof Boolean) out.addProperty("loaded", (Boolean) value);
        } catch (ReflectiveOperationException ignored) {}
        return out;
    }
}
