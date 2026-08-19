package dev.raistey.raisskysecrets;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(RaisSkySecrets.MOD_ID)
public final class RaisSkySecrets {
    public static final String MOD_ID = "raissky_secrets";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RaisSkySecrets(FMLJavaModLoadingContext context) {
        ClientController.register();
        LOGGER.info("RaisSky Secrets 0.1.3 initialized for Minecraft 26.2 / Forge");
    }
}
