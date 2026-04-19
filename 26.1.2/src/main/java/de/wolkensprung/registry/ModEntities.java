package de.wolkensprung.registry;

import de.wolkensprung.WolkensprungMod;
import de.wolkensprung.entity.QuestGiverEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    private static final Identifier WOLKENSPRUNG_ID = Identifier.fromNamespaceAndPath(WolkensprungMod.MOD_ID, "wolkensprung_npc");
    private static final ResourceKey<EntityType<?>> WOLKENSPRUNG_KEY = ResourceKey.create(Registries.ENTITY_TYPE, WOLKENSPRUNG_ID);

    public static final EntityType<QuestGiverEntity> WOLKENSPRUNG_NPC = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            WOLKENSPRUNG_ID,
            FabricEntityTypeBuilder.create(MobCategory.MISC, QuestGiverEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .build(WOLKENSPRUNG_KEY)
    );

    private ModEntities() {}

    public static void register() {
        FabricDefaultAttributeRegistry.register(WOLKENSPRUNG_NPC, QuestGiverEntity.createAttributes());
        WolkensprungMod.LOGGER.info("Registered entities");
    }
}
