package org.mindcraft.dregora;

import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

final class ActionObservation {
    private ActionObservation() {}

    static JsonObject read(EntityPlayer player, JsonObject action) {
        JsonObject out = new JsonObject();
        out.addProperty("source", player.world.isRemote ? "client_synced" : "integrated_server");
        out.addProperty("dimension", player.dimension);
        out.addProperty("windowId", player.openContainer.windowId);
        if (action.has("expectedArmorSlot")) {
            out.add("expectedArmorSlot", action.get("expectedArmorSlot"));
            out.add("expectedArmor", action.get("expectedArmor"));
        }
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            inventory.add(stack.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString());
        }
        out.add("inventory", inventory);
        JsonObject equipment = new JsonObject();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values())
            equipment.addProperty(slot.getName(), player.getItemStackFromSlot(slot)
                .writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString());
        out.add("equipment", equipment);
        JsonObject effects = new JsonObject();
        player.getActivePotionEffects().forEach(effect -> effects.addProperty(
            String.valueOf(effect.getPotion().getRegistryName()), effect.getAmplifier()));
        out.add("effects", effects);
        if (action.get("type").getAsString().equals("attack")) {
            JsonObject args = action.getAsJsonObject("args");
            Entity target = player.world.getEntityByID(args.get("entityId").getAsInt());
            if (target instanceof EntityLivingBase && target.getUniqueID().toString().equals(args.get("uuid").getAsString()))
                out.addProperty("targetHealth", ((EntityLivingBase) target).getHealth());
        }
        if (action.has("blockPos")) {
            out.add("blockPos", action.get("blockPos"));
            JsonArray coordinates = action.getAsJsonArray("blockPos");
            BlockPos pos = new BlockPos(coordinates.get(0).getAsInt(), coordinates.get(1).getAsInt(), coordinates.get(2).getAsInt());
            JsonArray blocks = new JsonArray();
            blocks.add(player.world.getBlockState(pos).toString());
            for (net.minecraft.util.EnumFacing face : net.minecraft.util.EnumFacing.values())
                blocks.add(player.world.getBlockState(pos.offset(face)).toString());
            out.add("blocks", blocks);
        }
        return out;
    }

    static JsonObject compare(JsonObject before, JsonObject after, String type) {
        JsonObject out = new JsonObject();
        out.addProperty("source", after.get("source").getAsString());
        JsonArray changes = new JsonArray();
        for (String key : new String[] {"inventory", "equipment", "effects", "blocks"})
            if (before.has(key) && after.has(key) && !before.get(key).equals(after.get(key))) changes.add(key);
        boolean damage = before.has("targetHealth") && after.has("targetHealth")
            && after.get("targetHealth").getAsFloat() < before.get("targetHealth").getAsFloat();
        if (damage) changes.add("targetHealth");
        // Inventory wear alone does not establish a successful attack or projectile hit.
        boolean equipped = before.has("expectedArmorSlot") && after.getAsJsonObject("equipment")
            .get(before.get("expectedArmorSlot").getAsString()).equals(before.get("expectedArmor"));
        boolean blockChanged = before.has("blocks") && !before.get("blocks").equals(after.get("blocks"));
        boolean containerOpened = before.has("windowId") && !before.get("windowId").equals(after.get("windowId"));
        boolean heldChanged = !before.getAsJsonObject("equipment").get("mainhand").equals(after.getAsJsonObject("equipment").get("mainhand"))
            || !before.getAsJsonObject("equipment").get("offhand").equals(after.getAsJsonObject("equipment").get("offhand"));
        boolean effectsChanged = !before.get("effects").equals(after.get("effects"));
        if (containerOpened) changes.add("windowId");
        boolean verified = type.equals("attack") ? damage : type.equals("equip_armor") ? equipped
            : type.equals("interact_block") ? blockChanged || containerOpened : heldChanged || effectsChanged;
        out.addProperty("meaning", type.equals("attack") ? "target_health_decreased" : type.equals("equip_armor")
            ? "requested_armor_equipped" : type.equals("interact_block") ? "block_changed_or_container_opened"
            : "item_equipment_or_effect_state_changed_not_projectile_hit");
        out.addProperty("status", verified ? "observed_change" : "unconfirmed");
        out.addProperty("effectVerified", verified);
        out.add("changes", changes); out.add("before", before); out.add("after", after);
        return out;
    }
}
