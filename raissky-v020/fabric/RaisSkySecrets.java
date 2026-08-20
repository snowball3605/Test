package dev.raistey.raisskysecrets.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RaisSkySecrets implements ClientModInitializer {
    public static final String MOD_ID = "raissky_secrets";
    public static final Logger LOGGER = LoggerFactory.getLogger("RaisSky Secrets");
    @Override public void onInitializeClient() {
        ClientController.register();
        LOGGER.info("RaisSky Secrets 0.2.0 initialized for Minecraft 26.2 / Fabric");
    }
}
