package org.mindcraft.dregora;

import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import java.lang.reflect.*;

final class Requirements {
    private Requirements() {}

    static ItemStack blockStack(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        ItemStack stack = new ItemStack(state.getBlock(), 1, state.getBlock().getMetaFromState(state));
        if (stack.isEmpty()) stack = state.getBlock().getItem(world, pos, state);
        TileEntity tile = world.getTileEntity(pos);
        // Reskillable matches the raw tile compound, not a BlockEntityTag wrapper.
        if (state.getBlock().hasTileEntity(state) && tile != null && !tile.isInvalid())
            stack.setTagCompound(tile.writeToNBT(new NBTTagCompound()));
        return stack;
    }

    static JsonObject read(EntityPlayer player, ItemStack stack) {
        return read(player, stack, false);
    }

    static JsonObject read(EntityPlayer player, ItemStack stack, boolean refresh) {
        JsonObject out = new JsonObject();
        out.addProperty("source", player.world.isRemote ? "client_synced" : "integrated_server");
        out.add("subject", identity(stack));
        JsonArray checks = new JsonArray(), missing = new JsonArray();
        out.add("requirements", checks); out.add("missing", missing);
        if (!Loader.isModLoaded("reskillable")) {
            out.addProperty("status", "unavailable"); out.addProperty("allowed", true);
            out.addProperty("reason", "mod_not_loaded"); return out;
        }
        try {
            Object holder = Class.forName("codersafterdark.reskillable.base.LevelLockHandler")
                .getMethod("getSkillLock", ItemStack.class).invoke(null, stack);
            Object data = Class.forName("codersafterdark.reskillable.api.data.PlayerDataHandler")
                .getMethod("get", EntityPlayer.class).invoke(null, player);
            // Action checks must not reuse eligibility cached before an advancement or custom condition changed.
            if (refresh) {
                Object cache = data.getClass().getMethod("getRequirementCache").invoke(data);
                cache.getClass().getMethod("forceClear").invoke(cache);
            }
            Class<?> requirementType = Class.forName("codersafterdark.reskillable.api.requirement.Requirement");
            Method achieved = data.getClass().getMethod("requirementAchieved", requirementType);
            boolean met = (Boolean) data.getClass().getMethod("matchStats", holder.getClass()).invoke(data, holder);
            for (Object requirement : (Iterable<?>) holder.getClass().getMethod("getRequirements").invoke(holder)) {
                JsonObject node = describe(requirement, data, achieved, 0);
                checks.add(node);
                // Keep an unsatisfied logical expression intact; its leaves are not necessarily all required.
                if (!node.get("achieved").getAsBoolean()) missing.add(node);
            }
            boolean bypass = player.isCreative() && !Class.forName("codersafterdark.reskillable.base.ConfigHandler")
                .getField("enforceOnCreative").getBoolean(null);
            out.addProperty("status", "available"); out.addProperty("requirementsMet", met);
            out.addProperty("allowed", met || bypass);
            out.addProperty("bypass", bypass ? "creative" : "none");
        } catch (Exception | LinkageError error) {
            out.addProperty("status", "unknown"); out.addProperty("allowed", false);
            out.addProperty("reason", "requirement_read_failed");
        }
        return out;
    }

    static JsonObject identity(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("empty", stack.isEmpty());
        if (!stack.isEmpty()) {
            out.addProperty("id", String.valueOf(stack.getItem().getRegistryName()));
            out.addProperty("metadata", stack.getMetadata());
            out.addProperty("nbt", stack.hasTagCompound() ? stack.getTagCompound().toString() : null);
        }
        return out;
    }

    static JsonObject describe(Object requirement, Object data, Method achieved, int depth) throws Exception {
        if (depth > 32) throw new IllegalStateException("requirement_tree_too_deep");
        JsonObject node = new JsonObject();
        String type = requirement.getClass().getSimpleName();
        node.addProperty("type", type); node.addProperty("class", requirement.getClass().getName());
        node.addProperty("achieved", (Boolean) achieved.invoke(data, requirement));
        node.addProperty("enabled", (Boolean) call(requirement, "isEnabled"));
        node.addProperty("description", String.valueOf(call(requirement, "getToolTip", data)));
        if (type.equals("SkillRequirement")) {
            Object skill = call(requirement, "getSkill");
            node.addProperty("skill", String.valueOf(call(skill, "getRegistryName")));
            node.addProperty("requiredLevel", (Number) call(requirement, "getLevel"));
            Object info = call(data, "getSkillInfo", skill);
            node.addProperty("currentLevel", (Number) call(info, "getLevel"));
        } else if (type.equals("AdvancementRequirement")) {
            Field name = requirement.getClass().getDeclaredField("advancementName"); name.setAccessible(true);
            node.addProperty("advancement", String.valueOf(name.get(requirement)));
            node.addProperty("resolved", call(requirement, "getAdvancement") != null);
        } else if (type.equals("TraitRequirement")) {
            node.addProperty("skill", String.valueOf(call(call(requirement, "getSkill"), "getRegistryName")));
            node.addProperty("trait", String.valueOf(call(call(requirement, "getUnlockable"), "getRegistryName")));
        }
        JsonArray children = new JsonArray();
        try {
            Object left = call(requirement, "getLeft"), right = call(requirement, "getRight");
            children.add(describe(left, data, achieved, depth + 1));
            children.add(describe(right, data, achieved, depth + 1));
        } catch (NoSuchMethodException absent) {
            try { children.add(describe(call(requirement, "getRequirement"), data, achieved, depth + 1)); }
            catch (NoSuchMethodException leaf) {}
        }
        if (children.size() > 0) node.add("children", children);
        return node;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) matches &= method.getParameterTypes()[i].isInstance(args[i]);
            if (matches) return method.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }
}
