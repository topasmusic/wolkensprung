package de.wolkensprung.client.render;

import de.wolkensprung.entity.QuestGiverEntity;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

public class QuestGiverEntityRenderer extends MobRenderer<QuestGiverEntity, AvatarRenderState, PlayerModel> {
    public static final ModelLayerLocation WOLKENSPRUNG_NPC_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath("wolkensprung", "wolkensprung_npc"), "main");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("wolkensprung", "textures/entity/quest_giver.png");
    private static final ClientAsset.Texture QUEST_GIVER_TEXTURE_ASSET = new ClientAsset.Texture() {
        @Override
        public Identifier id() {
            return TEXTURE;
        }

        @Override
        public Identifier texturePath() {
            return TEXTURE;
        }
    };
    private static final PlayerSkin QUEST_GIVER_SKIN =
            PlayerSkin.insecure(QUEST_GIVER_TEXTURE_ASSET, null, null, PlayerModelType.WIDE);

    public QuestGiverEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(WOLKENSPRUNG_NPC_LAYER), false), 0.5f);
    }

    public static LayerDefinition createModelData() {
        return LayerDefinition.create(
                PlayerModel.createMesh(CubeDeformation.NONE, false),
                64,
                64
        );
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(QuestGiverEntity entity, AvatarRenderState state, float tickDelta) {
        super.extractRenderState(entity, state, tickDelta);
        state.skin = QUEST_GIVER_SKIN;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return TEXTURE;
    }
}
