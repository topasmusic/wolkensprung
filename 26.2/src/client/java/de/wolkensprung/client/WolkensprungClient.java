package de.wolkensprung.client;

import de.wolkensprung.client.render.QuestGiverEntityRenderer;
import de.wolkensprung.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class WolkensprungClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(
                QuestGiverEntityRenderer.WOLKENSPRUNG_NPC_LAYER,
                QuestGiverEntityRenderer::createModelData
        );
        EntityRendererRegistry.register(ModEntities.WOLKENSPRUNG_NPC, QuestGiverEntityRenderer::new);
    }
}
