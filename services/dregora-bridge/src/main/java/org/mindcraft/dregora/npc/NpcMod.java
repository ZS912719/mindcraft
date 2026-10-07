package org.mindcraft.dregora.npc;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;

@Mod(modid = NpcMod.ID, name = "Mindcraft Dregora NPC", version = "0.1.0",
    acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
@Mod.EventBusSubscriber(modid = NpcMod.ID)
public final class NpcMod {
    public static final String ID = "mindcraft_dregora_npc";
    @SidedProxy(clientSide = "org.mindcraft.dregora.npc.NpcClientProxy", serverSide = "org.mindcraft.dregora.npc.NpcProxy")
    public static NpcProxy proxy;
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        EntityRegistry.registerModEntity(new ResourceLocation(ID, "teammate"), EntityTeammate.class,
            "mindcraft_teammate", 0, this, 64, 3, true);
        proxy.registerRenderer();
    }
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        NpcService.start(event.getServer());
        event.registerServerCommand(new NpcCommand());
    }
    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) { NpcService.stop(); }
    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) { NpcService.invalidate(event.player); }
    @SubscribeEvent
    public static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) { NpcService.invalidate(event.player); }
    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { NpcService.invalidate(event.player); }
}
