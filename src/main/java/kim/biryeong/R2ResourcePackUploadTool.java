package kim.biryeong;

import java.nio.file.Path;

public final class R2ResourcePackUploadTool {
	private static final String DEFAULT_PACK_PATH = "run/polymer/resource_pack.zip";

	private R2ResourcePackUploadTool() {
	}

	public static void main(String[] args) throws Exception {
		Path packPath = args.length > 0 ? Path.of(args[0]) : Path.of(DEFAULT_PACK_PATH);
		var config = R2UploaderModConfig.load();
		var upload = R2ResourcePackUpload.prepareUpload(packPath, config);

		System.out.println("Config: " + R2UploaderModConfig.resolveConfigPath().toAbsolutePath());
		System.out.println("Uploading " + upload.packPath());
		System.out.println("R2 object key: " + upload.objectKey());
		System.out.println("Expected public URL: " + upload.publicUrl());

		var result = R2ResourcePackUpload.uploadPack(packPath, config);
		System.out.println("Uploaded " + result.size() + " bytes");
		System.out.println("SHA-1: " + result.sha1());
		System.out.println("Content-Type: " + result.contentType());
	}
}
