package de.wolkensprung.registry;

import de.wolkensprung.WolkensprungMod;
import de.wolkensprung.entity.QuestGiverEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class ModEntities {
    private static final Identifier WOLKENSPRUNG_ID = Identifier.of(WolkensprungMod.MOD_ID, "wolkensprung_npc");
    private static final RegistryKey<EntityType<?>> WOLKENSPRUNG_KEY = RegistryKey.of(RegistryKeys.ENTITY_TYPE, WOLKENSPRUNG_ID);

    public static final EntityType<QuestGiverEntity> WOLKENSPRUNG_NPC = Registry.register(
            Registries.ENTITY_TYPE,
            WOLKENSPRUNG_ID,
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, QuestGiverEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .build(WOLKENSPRUNG_KEY)
    );

    private ModEntities() {}

    public static void register() {
        FabricDefaultAttributeRegistry.register(WOLKENSPRUNG_NPC, QuestGiverEntity.createAttributes());
        WolkensprungMod.LOGGER.info("Registered entities");
    }
}
