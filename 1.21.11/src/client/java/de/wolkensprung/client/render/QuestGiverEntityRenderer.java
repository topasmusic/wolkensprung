package de.wolkensprung.client.render;

import de.wolkensprung.entity.QuestGiverEntity;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

public class QuestGiverEntityRenderer extends MobEntityRenderer<QuestGiverEntity, PlayerEntityRenderState, PlayerEntityModel> {
    public static final EntityModelLayer WOLKENSPRUNG_NPC_LAYER = new EntityModelLayer(Identifier.of("wolkensprung", "wolkensprung_npc"), "main");
    private static final Identifier TEXTURE = Identifier.of("wolkensprung", "textures/entity/quest_giver.png");
    private static final AssetInfo.TextureAsset QUEST_GIVER_TEXTURE_ASSET = new AssetInfo.TextureAsset() {
        @Override
        public Identifier id() {
            return TEXTURE;
        }

        @Override
        public Identifier texturePath() {
            return TEXTURE;
        }
    };
    private static final SkinTextures QUEST_GIVER_SKIN =
            SkinTextures.create(QUEST_GIVER_TEXTURE_ASSET, null, null, PlayerSkinType.WIDE);

    public QuestGiverEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel(context.getPart(WOLKENSPRUNG_NPC_LAYER), false), 0.5f);
    }

    public static TexturedModelData createModelData() {
        return TexturedModelData.of(
                PlayerEntityModel.getTexturedModelData(Dilation.NONE, false),
                64,
                64
        );
    }

    @Override
    public PlayerEntityRenderState createRenderState() {
        return new PlayerEntityRenderState();
    }

    @Override
    public void updateRenderState(QuestGiverEntity entity, PlayerEntityRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.skinTextures = QUEST_GIVER_SKIN;
    }

    @Override
    public Identifier getTexture(PlayerEntityRenderState state) {
        return TEXTURE;
    }
}
