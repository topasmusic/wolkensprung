package de.wolkensprung.quest;

import de.wolkensprung.data.WolkensprungState;
import de.wolkensprung.entity.QuestGiverEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;

public final class WolkensprungService {
    private WolkensprungService() {}

    public static void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WolkensprungState.get(server).applyToRuntime();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WolkensprungState state = WolkensprungState.get(server);
            state.updateFromRuntime();
            server.overworld().getDataStorage().saveAndJoin();
        });

        ServerTickEvents.END_SERVER_TICK.register(WolkensprungQuest::onServerTick);

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !WolkensprungQuest.isQuestGiver(entity));

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                return WolkensprungQuest.onBlockUse(sw, sp, hand, hit);
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (entity instanceof QuestGiverEntity) {
                    return InteractionResult.PASS;
                }
                return WolkensprungQuest.onVillagerInteract(sw, sp, hand, entity);
            }
            return InteractionResult.PASS;
        });
    }
}
