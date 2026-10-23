package org.mindcraft.dregora.npc;

import com.google.gson.*;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import java.util.UUID;

final class NpcCommand extends CommandBase {
    @Override public String getName() { return "mindcraft_npc"; }
    @Override public String getUsage(ICommandSender sender) { return "/mindcraft_npc spawn|list|follow <uuid>|hold <uuid>|summon <uuid>|retreat <uuid> <x> <y> <z>|navigate <uuid> <x> <y> <z>|supply <uuid> <playerSlot 0..35> <count 1..64>"; }
    @Override public int getRequiredPermissionLevel() { return 2; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        try {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            NpcService.testOwner(server, player.getUniqueID());
            if (args.length == 2 && "test_dimension".equals(args[0])) {
                int dimension = Integer.parseInt(args[1]);
                if (!player.isCreative() || (dimension != 0 && dimension != -1) || dimension == player.dimension)
                    throw new IllegalArgumentException("invalid_test_dimension");
                if (server.getWorld(dimension) == null) net.minecraftforge.common.DimensionManager.initDimension(dimension);
                if (server.getWorld(dimension) == null) throw new IllegalStateException("test_dimension_unavailable");
                final double targetX=player.posX,targetY=player.posY,targetZ=player.posZ;
                // Direct fixture travel has no portal-entry vector; the default portal placer requires one.
                player.changeDimension(dimension,new net.minecraftforge.common.util.ITeleporter() {
                    @Override public void placeEntity(net.minecraft.world.World world,net.minecraft.entity.Entity entity,float yaw) {
                        double y=world.provider.getDimension()==-1?world.getActualHeight():targetY;
                        entity.setLocationAndAngles(targetX,y,targetZ,yaw,entity.rotationPitch);
                        entity.motionX=entity.motionY=entity.motionZ=0; entity.fallDistance=0;
                    }
                });
            } else if (args.length == 1 && "spawn".equals(args[0])) {
                if (!player.isEntityAlive() || player.isSpectator()) throw new IllegalStateException("actor_unavailable");
                if (NpcService.state(server, player.getUniqueID()).getAsJsonArray("npcs").size() >= 8)
                    throw new IllegalArgumentException("loaded_npc_limit");
                EntityTeammate npc = new EntityTeammate(player.world);
                Vec3d position = player.getPositionVector().addVector(2, 0, 0);
                npc.setPosition(position.x, position.y, position.z);
                if (!NpcService.safeDestination(npc, position)) throw new IllegalArgumentException("spawn_location_unsafe");
                npc.setOwner(player.getUniqueID());
                if (!player.world.spawnEntity(npc)) throw new IllegalStateException("spawn_rejected");
                sender.sendMessage(new TextComponentString("Mindcraft NPC: " + npc.getUniqueID()));
            } else if (args.length == 1 && "list".equals(args[0])) {
                for (JsonElement element : NpcService.state(server, player.getUniqueID()).getAsJsonArray("npcs")) {
                    JsonObject npc = element.getAsJsonObject();
                    sender.sendMessage(new TextComponentString(npc.get("uuid").getAsString() + " " + npc.get("command").getAsString()
                        + " " + npc.get("movement").getAsString()));
                }
            } else if (args.length == 4 && "supply".equals(args[0])) {
                EntityTeammate npc=NpcService.find(player,UUID.fromString(args[1]));
                NpcBuilding.supply(player,npc,Integer.parseInt(args[2]),Integer.parseInt(args[3]));
                sender.sendMessage(new TextComponentString("Mindcraft NPC supplies transferred."));
            } else if ((args.length == 2 && ("follow".equals(args[0]) || "hold".equals(args[0]) || "summon".equals(args[0])))
                || (args.length == 5 && ("retreat".equals(args[0]) || "navigate".equals(args[0])))) {
                JsonObject request = new JsonObject();
                request.addProperty("id", UUID.randomUUID().toString());
                request.add("session", NpcService.state(server, player.getUniqueID()).get("session"));
                request.addProperty("uuid", args[1]); request.addProperty("command", args[0]);
                if (args.length == 5) {
                    JsonObject target = new JsonObject();
                    for (int i = 0; i < 3; i++) target.addProperty(new String[] {"x", "y", "z"}[i], Double.parseDouble(args[i + 2]));
                    request.add("destination", target);
                }
                NpcService.execute(server, player.getUniqueID(), request, System.currentTimeMillis() + 2000);
                sender.sendMessage(new TextComponentString("Mindcraft NPC command set: " + args[0]));
            } else throw new WrongUsageException(getUsage(sender));
        } catch (IllegalArgumentException | IllegalStateException exception) { throw new CommandException(exception.getMessage()); }
    }
}
