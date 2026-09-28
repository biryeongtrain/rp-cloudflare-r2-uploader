package kim.biryeong;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
		normalizesBaseUrlTrailingSlashes();
		buildsExpectedR2Urls();
		failsOverDnsAddressesWithoutRetryingHttpErrorsOrFollowingRedirects();
		System.out.println("R2 uploader checks passed: paths, DNS failover, signed body, HTTP errors, redirects.");
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
