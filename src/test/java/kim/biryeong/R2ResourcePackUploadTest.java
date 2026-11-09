package kim.biryeong;

import com.sun.net.httpserver.HttpServer;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class R2ResourcePackUploadTest {
	private R2ResourcePackUploadTest() {
	}

	public static void main(String[] args) throws Exception {
		usesPolymerAutoHostMainPackPathForObjectKey();
		separatesNamedPacksAndChangesUrlsWithContent();
		loadsAdditionalPacksWithoutBreakingOldConfiguration();
		rejectsInvalidAndDuplicatePackIds();
		normalizesBaseUrlTrailingSlashes();
		buildsExpectedR2Urls();
		handlesPolymerZipCompletionWithoutCredentials();
		failsOverDnsAddressesWithoutRetryingHttpErrorsOrFollowingRedirects();
		System.out.println("R2 uploader checks passed: named pack isolation, content hashes, old/new config, invalid ids, Polymer completion, DNS failover, signed body, HTTP errors, redirects.");
	}

	private static void handlesPolymerZipCompletionWithoutCredentials() throws Exception {
		var directory = Files.createTempDirectory("r2-polymer-completion-");
		var config = directory.resolve("rp-r2-uploader.json");
		String previous = System.getProperty(R2UploaderModConfig.CONFIG_PROPERTY);
		System.setProperty(R2UploaderModConfig.CONFIG_PROPERTY, config.toString());
		try {
			R2ResourcePackUpload.uploadMainPack(new Object());
			if (Files.exists(config)) {
				throw new AssertionError("Non-ZIP output must not trigger uploader configuration");
			}
			R2ResourcePackUpload.uploadMainPack(new OutputGenerator.Result(directory.resolve("pack.zip"), "hash", false));
			if (!Files.isRegularFile(config)) {
				throw new AssertionError("ZIP completion must reach the uploader's missing-config handling");
			}
		} finally {
			if (previous == null) {
				System.clearProperty(R2UploaderModConfig.CONFIG_PROPERTY);
			} else {
				System.setProperty(R2UploaderModConfig.CONFIG_PROPERTY, previous);
			}
			Files.deleteIfExists(config);
			Files.delete(directory);
		}
	}

	private static void usesPolymerAutoHostMainPackPathForObjectKey() {
		String sha1 = "dedfa6803b16bbc0c271da1d21963aa494c7dcbb";
		assertEquals(
				"polymer/resources+dedfa6803b16bbc0c271da1d21963aa494c7dcbb.zip",
				R2ResourcePackUpload.objectKey(sha1)
		);
	}

	private static void separatesNamedPacksAndChangesUrlsWithContent() throws Exception {
		Path directory = Files.createTempDirectory("r2-named-packs-");
		Path zip = directory.resolve("activity.zip");
		var config = new R2ResourcePackUpload.R2UploadConfig("account", "access", "secret", "bucket", "https://packs.example.com///");
		try {
			Files.writeString(zip, "first pack bytes");
			var main = R2ResourcePackUpload.prepareUpload(zip, config);
			var exodus = R2ResourcePackUpload.prepareUpload("destiny:exodus_crash", zip, config);
			var lair = R2ResourcePackUpload.prepareUpload("destiny:strikes/devils_lair", zip, config);
			assertEquals(main.sha1(), exodus.sha1());
			assertEquals("destiny/exodus_crash+" + exodus.sha1() + ".zip", exodus.objectKey());
			assertEquals("destiny/strikes/devils_lair+" + lair.sha1() + ".zip", lair.objectKey());
			assertEquals("https://packs.example.com/" + exodus.objectKey(), exodus.publicUrl());
			assertEquals("https://account.r2.cloudflarestorage.com/bucket/destiny/exodus_crash%2B" + exodus.sha1() + ".zip", exodus.objectUri().toString());
			Files.writeString(zip, "second pack bytes");
			var updated = R2ResourcePackUpload.prepareUpload("destiny:exodus_crash", zip, config);
			if (updated.objectKey().equals(exodus.objectKey())) throw new AssertionError("Changed bytes must change the public pack URL");
			assertEquals("first pack bytes", new String(exodus.packBytes(), StandardCharsets.UTF_8));
			try {
				R2ResourcePackUpload.prepareUpload("destiny:missing", directory.resolve("missing.zip"), config);
				throw new AssertionError("Missing file must not be uploaded");
			} catch (IOException expected) { }
		} finally {
			Files.deleteIfExists(zip);
			Files.delete(directory);
		}
	}

	private static void loadsAdditionalPacksWithoutBreakingOldConfiguration() throws Exception {
		withConfig("{\"accountId\":\"account\"}", () -> {
			var settings = R2UploaderModConfig.loadSettings();
			assertEquals("account", settings.upload().accountId());
			assertEquals("0", Integer.toString(settings.additionalPacks().size()));
		});
		withConfig("""
			{ "additionalPacks": [
			  { "id": "destiny:exodus_crash", "path": "packs/exodus.zip" },
			  { "id": "destiny:devils_lair", "path": "packs/lair.zip" }
			] }
			""", () -> {
			var packs = R2UploaderModConfig.loadSettings().additionalPacks();
			assertEquals("2", Integer.toString(packs.size()));
			assertEquals("destiny:exodus_crash", packs.getFirst().id());
			assertEquals(Path.of("packs/exodus.zip").toString(), packs.getFirst().path().toString());
		});
	}

	private static void rejectsInvalidAndDuplicatePackIds() throws Exception {
		for (String id : new String[]{"exodus", ".:pack", "..:pack", "Destiny:exodus", "destiny:../pack", "destiny:a/../b", "destiny:/a", "destiny:a//b", "destiny:a/", "destiny:a?key=bad"}) {
			try {
				R2ResourcePackUpload.objectKey(id, "dedfa6803b16bbc0c271da1d21963aa494c7dcbb");
				throw new AssertionError("Expected invalid id to fail: " + id);
			} catch (IllegalArgumentException expected) { }
		}
		for (String packs : new String[]{
				"[{\"id\":\"polymer:resources\",\"path\":\"extra.zip\"}]",
				"[{\"id\":\"destiny:a\",\"path\":\"a.zip\"},{\"id\":\"destiny:a\",\"path\":\"b.zip\"}]",
				"[{\"id\":\"destiny:a\"}]", "[null]", "null"}) {
			withConfig("{\"additionalPacks\":" + packs + "}", () -> {
				try {
					R2UploaderModConfig.loadSettings();
					throw new AssertionError("Invalid additional pack configuration must fail");
				} catch (IOException expected) { }
			});
		}
	}

	@FunctionalInterface
	private interface CheckedRun { void run() throws Exception; }

	private static void withConfig(String json, CheckedRun action) throws Exception {
		Path directory = Files.createTempDirectory("r2-additional-config-");
		Path config = directory.resolve("config.json");
		String previous = System.getProperty(R2UploaderModConfig.CONFIG_PROPERTY);
		try {
			Files.writeString(config, json);
			System.setProperty(R2UploaderModConfig.CONFIG_PROPERTY, config.toString());
			action.run();
		} finally {
			if (previous == null) System.clearProperty(R2UploaderModConfig.CONFIG_PROPERTY);
			else System.setProperty(R2UploaderModConfig.CONFIG_PROPERTY, previous);
			Files.deleteIfExists(config);
			Files.delete(directory);
		}
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

	private static void failsOverDnsAddressesWithoutRetryingHttpErrorsOrFollowingRedirects() throws Exception {
		InetAddress reachable = InetAddress.getByName("127.0.0.1");
		InetAddress unreachable = InetAddress.getByName("127.0.0.2");
		HttpServer server = HttpServer.create(new InetSocketAddress(reachable, 0), 0);
		AtomicInteger status = new AtomicInteger(200);
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<String> received = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> signed = new AtomicReference<>();
		AtomicReference<String> path = new AtomicReference<>();
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			signed.set(exchange.getRequestHeaders().getFirst("Authorization"));
			path.set(exchange.getRequestURI().getRawPath());
			exchange.getResponseHeaders().set("Location", "/redirected");
			exchange.sendResponseHeaders(status.get(), -1);
			exchange.close();
		});
		server.start();
		try (var client = R2ResourcePackUpload.uploadClientBuilder()
				.setDnsResolver(host -> new InetAddress[]{unreachable, reachable}).build()) {
			var uploader = new R2ResourcePackUpload.R2SignedUploader(client,
					Clock.fixed(Instant.parse("2026-09-26T09:00:00Z"), ZoneOffset.UTC));
			var config = new R2ResourcePackUpload.R2UploadConfig("account", "access", "secret", "bucket", "https://packs.example.com");
			String key = "polymer/resources+test.zip";
			URI uri = URI.create("http://r2.test:" + server.getAddress().getPort() + "/bucket/polymer/resources%2Btest.zip");
			byte[] body = "test resource pack".getBytes(StandardCharsets.UTF_8);
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
			uploader.putObject(config, key, uri, body, hash);
			assertEquals("test resource pack", received.get());
			assertEquals("application/zip", contentType.get());
			assertEquals("/bucket/polymer/resources%2Btest.zip", path.get());
			if (signed.get() == null || !signed.get().startsWith("AWS4-HMAC-SHA256 Credential=access/20260926/auto/s3/aws4_request,")) {
				throw new AssertionError("Missing expected S3 signature");
			}
			assertEquals("1", Integer.toString(requests.get()));
			// Dedicated packs use the same signed transport, with their own content-addressed path.
			String activityKey = R2ResourcePackUpload.objectKey("destiny:exodus_crash",
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body)));
			URI activityUri = URI.create("http://r2.test:" + server.getAddress().getPort() + "/bucket/" + activityKey.replace("+", "%2B"));
			uploader.putObject(config, activityKey, activityUri, body, hash);
			assertEquals("/bucket/" + activityKey.replace("+", "%2B"), path.get());
			assertEquals("test resource pack", received.get());
			assertEquals("2", Integer.toString(requests.get()));
			for (int failure : new int[]{403, 500, 307}) {
				status.set(failure);
				int before = requests.get();
				try {
					uploader.putObject(config, key, uri, body, hash);
					throw new AssertionError("Expected HTTP " + failure + " to fail");
				} catch (IOException expected) {
					assertEquals("R2 PUT failed with HTTP " + failure + ": ", expected.getMessage());
				}
				assertEquals("1", Integer.toString(requests.get() - before));
			}
		} finally {
			server.stop(0);
		}
	}
}
