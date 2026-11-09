package kim.biryeong;

import eu.pb4.polymer.autohost.api.AutoHostUtils;
import java.nio.file.Path;

public final class R2ResourcePackUploadTool {
	private static final String DEFAULT_PACK_PATH = "run/polymer/resource_pack.zip";

	private R2ResourcePackUploadTool() { }

	public static void main(String[] args) throws Exception {
		boolean all = args.length > 0 && args[0].equals("--all");
		int offset = all ? 1 : 0;
		Path packPath = args.length > offset ? Path.of(args[offset]) : Path.of(DEFAULT_PACK_PATH);
		String packId = !all && args.length > offset + 1 ? args[offset + 1] : AutoHostUtils.DEFAULT_PACK_ID.toString();
		var settings = R2UploaderModConfig.loadSettings();
		System.out.println("Config: " + R2UploaderModConfig.resolveConfigPath().toAbsolutePath());
		if (all) {
			// Catch absent files before starting any network uploads; process bytes one pack at a time.
			checkFile(packPath);
			for (var pack : settings.additionalPacks()) checkFile(pack.path());
		}
		upload(packId, packPath, settings.upload());
		if (all) for (var pack : settings.additionalPacks()) upload(pack.id(), pack.path(), settings.upload());
	}

	private static void checkFile(Path path) throws java.io.IOException {
		if (!java.nio.file.Files.isRegularFile(path)) throw new java.io.IOException("Resource pack file does not exist: " + path);
	}

	private static void upload(String id, Path path, R2ResourcePackUpload.R2UploadConfig config) throws Exception {
		var result = R2ResourcePackUpload.uploadPack(id, path, config);
		System.out.println("Uploaded pack: " + id);
		System.out.println("R2 object key: " + result.objectKey());
		System.out.println("Public URL: " + result.publicUrl());
		System.out.println("Uploaded " + result.size() + " bytes");
		System.out.println("SHA-1: " + result.sha1());
		System.out.println("Content-Type: " + result.contentType());
	}
}
