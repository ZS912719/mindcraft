package org.mindcraft.dregora.npc;

import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

public final class NpcClientProxy extends NpcProxy {
    @Override public void registerRenderer() {
        RenderingRegistry.registerEntityRenderingHandler(EntityTeammate.class, TeammateRenderer::new);
    }
    private static final class TeammateRenderer extends RenderBiped<EntityTeammate> {
        TeammateRenderer(RenderManager manager) {
            super(manager, new ModelPlayer(0.0F, true), 0.5F);
            addLayer(new LayerBipedArmor(this));
        }
        @Override protected ResourceLocation getEntityTexture(EntityTeammate entity) {
            return new ResourceLocation(NpcMod.ID, "textures/entity/teammate.png");
        }
    }
}
