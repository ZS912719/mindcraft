package org.mindcraft.dregora;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import java.util.ArrayList;
import java.util.List;

final class GameState {
    private GameState() {}

    static JsonObject stack(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("empty", stack.isEmpty());
        if (!stack.isEmpty()) {
            out.addProperty("id", String.valueOf(stack.getItem().getRegistryName()));
            out.addProperty("metadata", stack.getMetadata());
            out.addProperty("count", stack.getCount());
            out.addProperty("damage", stack.getItemDamage());
            out.addProperty("maxDamage", stack.getMaxDamage());
            out.addProperty("name", stack.getDisplayName());
            out.addProperty("nbt", stack.hasTagCompound() ? stack.getTagCompound().toString() : null);
            out.add("profile", ItemProfile.read(stack));
        }
        return out;
    }

    static JsonObject snapshot(Minecraft mc, String session) {
        JsonObject out = new JsonObject();
        out.addProperty("protocol", 1);
        out.addProperty("timestamp", System.currentTimeMillis());
        out.addProperty("session", session);
        out.addProperty("connected", mc.player != null && mc.world != null);
        if (mc.player == null || mc.world == null) return out;
        out.addProperty("dimension", mc.player.dimension);
        out.addProperty("player", mc.player.getName());
        out.addProperty("alive", mc.player.isEntityAlive());
        out.addProperty("health", mc.player.getHealth());
        out.addProperty("maxHealth", mc.player.getMaxHealth());
        out.addProperty("food", mc.player.getFoodStats().getFoodLevel());
        out.addProperty("onGround", mc.player.onGround);
        out.addProperty("burning", mc.player.isBurning());
        out.addProperty("inWater", mc.player.isInWater());
        out.addProperty("guiOpen", mc.currentScreen != null);
        out.add("combat", CombatState.read(mc));
        out.addProperty("eyeHeight", mc.player.getEyeHeight());
        out.addProperty("armor", mc.player.getTotalArmorValue());
        JsonArray effects = new JsonArray();
        mc.player.getActivePotionEffects().forEach(effect -> {
            JsonObject data = new JsonObject();
            data.addProperty("id", String.valueOf(effect.getPotion().getRegistryName()));
            data.addProperty("amplifier", effect.getAmplifier());
            data.addProperty("duration", effect.getDuration());
            effects.add(data);
        });
        out.add("effects", effects);
        JsonArray actions = new JsonArray();
        for (String action : new String[] {"stop", "move", "look", "select_slot", "attack", "use_item", "interact_block", "equip_armor"}) actions.add(action);
        if ("1".equals(System.getenv("MINDCRAFT_BRIDGE_TEST_MODE")) && mc.getIntegratedServer() != null) {
            actions.add("test_command"); actions.add("test_lock"); actions.add("test_advancement");
        }
        out.add("supportedActions", actions);
        JsonObject position = new JsonObject();
        position.addProperty("x", mc.player.posX);
        position.addProperty("y", mc.player.posY);
        position.addProperty("z", mc.player.posZ);
        position.addProperty("yaw", mc.player.rotationYaw);
        position.addProperty("pitch", mc.player.rotationPitch);
        out.add("position", position);
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < mc.player.inventory.getSizeInventory(); i++) {
            JsonObject item = stack(mc.player.inventory.getStackInSlot(i));
            if (!mc.player.inventory.getStackInSlot(i).isEmpty())
                item.add("eligibility", Requirements.read(mc.player, mc.player.inventory.getStackInSlot(i)));
            item.addProperty("slot", i);
            inventory.add(item);
        }
        out.add("inventory", inventory);
        out.addProperty("selectedSlot", mc.player.inventory.currentItem);
        JsonObject equipment = new JsonObject();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            equipment.add(slot.getName(), stack(mc.player.getItemStackFromSlot(slot)));
        }
        out.add("equipment", equipment);
        JsonObject container = new JsonObject();
        container.addProperty("windowId", mc.player.openContainer.windowId);
        JsonArray slots = new JsonArray();
        mc.player.openContainer.inventorySlots.forEach(slot -> {
            JsonObject item = stack(slot.getStack());
            item.addProperty("slot", slot.slotNumber);
            slots.add(item);
        });
        container.add("slots", slots);
        out.add("container", container);
        JsonArray entities = new JsonArray();
        List<Entity> observed = mc.world.getEntitiesWithinAABBExcludingEntity(mc.player, mc.player.getEntityBoundingBox().grow(96));
        observed.sort(java.util.Comparator.comparingDouble(mc.player::getDistanceSq));
        out.addProperty("entityObservationRadius", 96);
        out.addProperty("entitiesTruncated", observed.size() > 256);
        for (Entity entity : observed) {
            if (entities.size() >= 256) break;
            JsonObject data = new JsonObject();
            data.addProperty("entityId", entity.getEntityId());
            data.addProperty("uuid", entity.getUniqueID().toString());
            data.addProperty("type", String.valueOf(EntityList.getKey(entity)));
            data.addProperty("name", entity.getName());
            data.addProperty("x", entity.posX);
            data.addProperty("y", entity.posY);
            data.addProperty("z", entity.posZ);
            data.addProperty("distance", mc.player.getDistance(entity));
            data.addProperty("visible", mc.player.canEntityBeSeen(entity));
            JsonObject bounds = new JsonObject();
            net.minecraft.util.math.AxisAlignedBB box = entity.getEntityBoundingBox();
            bounds.addProperty("minX", box.minX); bounds.addProperty("minY", box.minY); bounds.addProperty("minZ", box.minZ);
            bounds.addProperty("maxX", box.maxX); bounds.addProperty("maxY", box.maxY); bounds.addProperty("maxZ", box.maxZ);
            data.add("bounds", bounds);
            JsonObject velocity = new JsonObject();
            velocity.addProperty("x", entity.motionX); velocity.addProperty("y", entity.motionY); velocity.addProperty("z", entity.motionZ);
            data.add("velocity", velocity);
            if (entity instanceof EntityLivingBase) data.addProperty("health", ((EntityLivingBase) entity).getHealth());
            entities.add(data);
        }
        out.add("entities", entities);
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            JsonObject block = new JsonObject();
            block.addProperty("id", String.valueOf(mc.world.getBlockState(hit.getBlockPos()).getBlock().getRegistryName()));
            block.addProperty("metadata", mc.world.getBlockState(hit.getBlockPos()).getBlock().getMetaFromState(mc.world.getBlockState(hit.getBlockPos())));
            block.addProperty("state", mc.world.getBlockState(hit.getBlockPos()).toString());
            block.addProperty("x", hit.getBlockPos().getX());
            block.addProperty("y", hit.getBlockPos().getY());
            block.addProperty("z", hit.getBlockPos().getZ());
            block.add("eligibility", Requirements.read(mc.player, Requirements.blockStack(mc.world, hit.getBlockPos())));
            out.add("targetBlock", block);
        }
        out.add("mods", ModState.read(mc.player));
        JsonObject eligibility = new JsonObject();
        eligibility.add("mainhand", Requirements.read(mc.player, mc.player.getHeldItemMainhand()));
        eligibility.add("offhand", Requirements.read(mc.player, mc.player.getHeldItemOffhand()));
        out.add("eligibility", eligibility);
        return out;
    }

    static JsonObject catalog(String kind, int offset, int limit) {
        List<JsonObject> rows = new ArrayList<>();
        int total;
        if (kind.equals("items")) {
            total = ForgeRegistries.ITEMS.getValuesCollection().size();
            int index = 0;
            for (net.minecraft.item.Item item : ForgeRegistries.ITEMS) {
                if (index++ < offset || index > offset + limit) continue;
                JsonObject row = new JsonObject();
                row.addProperty("id", String.valueOf(item.getRegistryName()));
                row.addProperty("numericId", net.minecraft.item.Item.getIdFromItem(item));
                row.add("defaultStack", stack(new ItemStack(item)));
                net.minecraft.util.NonNullList<ItemStack> variants = net.minecraft.util.NonNullList.create();
                item.getSubItems(net.minecraft.creativetab.CreativeTabs.SEARCH, variants);
                JsonArray variantRows = new JsonArray();
                for (ItemStack variant : variants) variantRows.add(stack(variant));
                row.add("creativeVariants", variantRows);
                rows.add(row);
            }
        }
        else if (kind.equals("blocks")) {
            total = ForgeRegistries.BLOCKS.getValuesCollection().size();
            int index = 0;
            for (net.minecraft.block.Block block : ForgeRegistries.BLOCKS) {
                if (index++ < offset || index > offset + limit) continue;
                JsonObject row = new JsonObject();
                row.addProperty("id", String.valueOf(block.getRegistryName()));
                row.addProperty("numericId", net.minecraft.block.Block.getIdFromBlock(block));
                rows.add(row);
            }
        }
        else if (kind.equals("recipes")) {
            total = ForgeRegistries.RECIPES.getValuesCollection().size();
            int index = 0;
            for (IRecipe recipe : ForgeRegistries.RECIPES) {
                if (index++ < offset || index > offset + limit) continue;
                JsonObject row = new JsonObject();
                row.addProperty("id", String.valueOf(recipe.getRegistryName()));
                row.addProperty("dynamic", recipe.isDynamic());
                row.addProperty("class", recipe.getClass().getName());
                row.add("output", stack(recipe.getRecipeOutput()));
                JsonArray ingredients = new JsonArray();
                for (Ingredient ingredient : recipe.getIngredients()) {
                    JsonArray choices = new JsonArray();
                    for (ItemStack match : ingredient.getMatchingStacks()) choices.add(stack(match));
                    ingredients.add(choices);
                }
                row.add("ingredients", ingredients);
                row.addProperty("executable", false);
                rows.add(row);
            }
        }
        else throw new IllegalArgumentException("invalid_catalog");
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind);
        result.addProperty("total", total);
        result.addProperty("offset", offset);
        JsonArray page = new JsonArray();
        for (JsonObject row : rows) page.add(row);
        result.add("entries", page);
        return result;
    }
}
