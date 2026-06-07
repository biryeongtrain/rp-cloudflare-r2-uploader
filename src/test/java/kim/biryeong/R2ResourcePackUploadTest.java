package kim.biryeong;

public final class R2ResourcePackUploadTest {
	private R2ResourcePackUploadTest() {
	}

	public static void main(String[] args) {
		usesPolymerAutoHostMainPackPathForObjectKey();
		normalizesBaseUrlTrailingSlashes();
		buildsExpectedR2Urls();
	}

	private static void usesPolymerAutoHostMainPackPathForObjectKey() {
		String sha1 = "dedfa6803b16bbc0c271da1d21963aa494c7dcbb";
		assertEquals(
				"polymer/resources+dedfa6803b16bbc0c271da1d21963aa494c7dcbb.zip",
				R2ResourcePackUpload.objectKey(sha1)
		);
	}

	private static void normalizesBaseUrlTrailingSlashes() {
		assertEquals("https://packs.example.com", R2ResourcePackUpload.normalizeBaseUrl("https://packs.example.com"));
		assertEquals("https://packs.example.com", R2ResourcePackUpload.normalizeBaseUrl("https://packs.example.com/"));
		assertEquals("https://packs.example.com", R2ResourcePackUpload.normalizeBaseUrl("https://packs.example.com///"));
	}

	private static void buildsExpectedR2Urls() {
		var config = new R2ResourcePackUpload.R2UploadConfig(
				"account-id",
				"access-key",
				"secret-key",
				"pack-bucket",
				"https://packs.example.com/"
		);
		String objectKey = "polymer/resources+dedfa6803b16bbc0c271da1d21963aa494c7dcbb.zip";

		assertEquals(
				"https://account-id.r2.cloudflarestorage.com/pack-bucket/polymer/resources%2Bdedfa6803b16bbc0c271da1d21963aa494c7dcbb.zip",
				config.objectUri(objectKey).toString()
		);
		assertEquals(
				"https://packs.example.com/polymer/resources+dedfa6803b16bbc0c271da1d21963aa494c7dcbb.zip",
				config.publicUrl(objectKey)
		);
	}

	private static void assertEquals(String expected, String actual) {
		if (!expected.equals(actual)) {
			throw new AssertionError("Expected '" + expected + "' but got '" + actual + "'");
		}
	}
}
