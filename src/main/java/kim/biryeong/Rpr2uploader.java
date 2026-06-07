package kim.biryeong;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rpr2uploader implements ModInitializer {
	public static final String MOD_ID = "rp-r2-uploader";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		R2ResourcePackUpload.register();
		LOGGER.info("R2 resource pack uploader initialized");
	}
}
