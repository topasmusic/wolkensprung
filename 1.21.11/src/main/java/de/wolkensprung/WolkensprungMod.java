package de.wolkensprung;

import de.wolkensprung.command.WolkensprungCommands;
import de.wolkensprung.quest.WolkensprungService;
import de.wolkensprung.registry.ModEntities;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WolkensprungMod implements ModInitializer {
    public static final String MOD_ID = "wolkensprung";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);
        ModEntities.register();
        WolkensprungService.registerEvents();
        WolkensprungCommands.register();
    }
}