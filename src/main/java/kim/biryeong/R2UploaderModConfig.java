package kim.biryeong;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import eu.pb4.polymer.autohost.api.AutoHostUtils;

public final class R2UploaderModConfig {
	public static final String CONFIG_PROPERTY = "rpr2uploader.config";
	private static final String CONFIG_FILE_NAME = "rp-r2-uploader.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private R2UploaderModConfig() {
	}

	public static R2ResourcePackUpload.R2UploadConfig load() throws IOException {
		return loadSettings().upload();
	}

	public record AdditionalPack(String id, Path path) { }
	public record Settings(R2ResourcePackUpload.R2UploadConfig upload, List<AdditionalPack> additionalPacks) {
		public Settings { additionalPacks = List.copyOf(additionalPacks); }
	}

	public static Settings loadSettings() throws IOException {
		Path configPath = resolveConfigPath();

		if (Files.notExists(configPath)) {
			writeDefaultConfig(configPath);
			throw new IOException("Created missing R2 uploader config at " + configPath.toAbsolutePath()
					+ ". Fill it with your Cloudflare R2 values before uploading.");
		}

		try {
			FileConfig fileConfig = GSON.fromJson(Files.readString(configPath), FileConfig.class);
			if (fileConfig == null) {
				fileConfig = new FileConfig();
			}
			return new Settings(fileConfig.toUploadConfig(), fileConfig.packs());
		} catch (JsonSyntaxException | IllegalArgumentException e) {
			throw new IOException("Invalid R2 uploader config at " + configPath.toAbsolutePath() + ": " + e.getMessage(), e);
		}
	}

	public static Path resolveConfigPath() {
		String override = System.getProperty(CONFIG_PROPERTY);
		if (override != null && !override.isBlank()) {
			return Path.of(override.trim());
		}

		try {
			return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
		} catch (Throwable ignored) {
			return Path.of("config").resolve(CONFIG_FILE_NAME);
		}
	}

	private static void writeDefaultConfig(Path configPath) throws IOException {
		Path parent = configPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(configPath, GSON.toJson(new FileConfig()));
	}

	private static final class PackFileConfig {
		String id;
		String path;
	}

	private static final class FileConfig {
		String accountId = "";
		String accessKeyId = "";
		String secretAccessKey = "";
		String bucket = "";
		String publicBaseUrl = "https://packs.example.com";
		List<PackFileConfig> additionalPacks = new ArrayList<>();

		private List<AdditionalPack> packs() {
			if (additionalPacks == null) throw new IllegalArgumentException("additionalPacks must be an array");
			var seen = new HashSet<String>();
			seen.add(AutoHostUtils.DEFAULT_PACK_ID.toString());
			var packs = new ArrayList<AdditionalPack>();
			for (var pack : additionalPacks) {
				if (pack == null) throw new IllegalArgumentException("additionalPacks contains null");
				String id = R2ResourcePackUpload.validatePackId(pack.id);
				if (!seen.add(id)) throw new IllegalArgumentException("Duplicate or reserved pack id: " + id);
				if (pack.path == null || pack.path.isBlank()) throw new IllegalArgumentException("Missing path for " + id);
				packs.add(new AdditionalPack(id, Path.of(pack.path)));
			}
			return List.copyOf(packs);
		}

		private R2ResourcePackUpload.R2UploadConfig toUploadConfig() {
			return new R2ResourcePackUpload.R2UploadConfig(
					blankToNull(accountId),
					blankToNull(accessKeyId),
					blankToNull(secretAccessKey),
					blankToNull(bucket),
					blankToNull(publicBaseUrl)
			);
		}

		private static String blankToNull(String value) {
			return value == null || value.isBlank() ? null : value.trim();
		}
	}
}
