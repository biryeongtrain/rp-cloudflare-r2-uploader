package kim.biryeong;

import com.google.gson.JsonElement;
import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.impl.AutoHost;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class R2ResourcePackUpload {
	private static final String CONTENT_TYPE = "application/zip";
	private static final String R2_SERVICE = "s3";
	private static final String R2_REGION = "auto";
	private static final DateTimeFormatter AMZ_DATE_FORMAT =
			DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DATE_STAMP_FORMAT =
			DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC);

	private R2ResourcePackUpload() {
	}

	public static void register() {
		PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(R2ResourcePackUpload::uploadMainPack);
	}

	private static void uploadMainPack() {
		Path packPath = PolymerResourcePackUtils.getMainPath();
		R2UploadConfig config;

		try {
			config = R2UploaderModConfig.load();
		} catch (IOException e) {
			Rpr2uploader.LOGGER.warn(
					"Skipping Polymer resource pack R2 upload: {} AutoHost may advertise an unavailable R2 URL until the config is filled in.",
					e.getMessage()
			);
			return;
		}

		if (!config.isComplete()) {
			Rpr2uploader.LOGGER.warn(
					"Skipping Polymer resource pack R2 upload: {} in {}. AutoHost may advertise an unavailable R2 URL until the config is filled in.",
					config.missingMessage(),
					R2UploaderModConfig.resolveConfigPath().toAbsolutePath()
			);
			return;
		}

		try {
			PreparedUpload upload = prepareUpload(packPath, config);

			if (!validateAutoHostConfig(config, upload.objectKey(), upload.publicUrl())) {
				return;
			}

			Rpr2uploader.LOGGER.info(
					"Uploading Polymer resource pack {} to R2 key {}. Expected AutoHost external URL: {}",
					packPath,
					upload.objectKey(),
					upload.publicUrl()
			);

			UploadResult result = uploadPrepared(config, upload);

			Rpr2uploader.LOGGER.info(
					"Uploaded Polymer resource pack to R2: key={}, size={} bytes, sha1={}, contentType={}",
					result.objectKey(),
					result.size(),
					result.sha1(),
					result.contentType()
			);
		} catch (Exception e) {
			Rpr2uploader.LOGGER.error(
				"Failed to upload Polymer resource pack to R2. AutoHost may advertise a URL that is not available until this is fixed.",
					e
			);
		}
	}

	static UploadResult uploadPack(Path packPath, R2UploadConfig config) throws Exception {
		return uploadPrepared(config, prepareUpload(packPath, config));
	}

	static PreparedUpload prepareUpload(Path packPath, R2UploadConfig config) throws Exception {
		if (!config.isComplete()) {
			throw new IllegalStateException("Cannot upload Polymer resource pack to R2: " + config.missingMessage());
		}

		if (!Files.isRegularFile(packPath)) {
			throw new IOException("Resource pack file does not exist: " + packPath);
		}

		byte[] packBytes = Files.readAllBytes(packPath);
		String sha1 = hexDigest("SHA-1", packBytes);
		String payloadSha256 = hexDigest("SHA-256", packBytes);
		String objectKey = objectKey(sha1);
		return new PreparedUpload(
				packPath,
				packBytes,
				sha1,
				payloadSha256,
				objectKey,
				config.objectUri(objectKey),
				config.publicUrl(objectKey)
		);
	}

	private static UploadResult uploadPrepared(R2UploadConfig config, PreparedUpload upload) throws Exception {
		var uploader = new R2SignedUploader(HttpClient.newHttpClient(), Clock.systemUTC());
		uploader.putObject(config, upload.objectKey(), upload.objectUri(), upload.packBytes(), upload.payloadSha256());
		return new UploadResult(upload.objectKey(), upload.publicUrl(), upload.sha1(), upload.packBytes().length, CONTENT_TYPE);
	}

	static String objectKey(String sha1) {
		return AutoHostUtils.getPathFromId(AutoHostUtils.DEFAULT_PACK_ID) + "+" + sha1 + ".zip";
	}

	private static boolean validateAutoHostConfig(R2UploadConfig config, String objectKey, String publicUrl) {
		if (!"polymer:external".equals(AutoHost.config.type)) {
			Rpr2uploader.LOGGER.warn(
					"R2 upload is enabled but Polymer AutoHost type is '{}', not 'polymer:external'",
					AutoHost.config.type
			);
		}

		if (!AutoHost.config.includeHashInName) {
			Rpr2uploader.LOGGER.error(
					"Refusing R2 upload because Polymer AutoHost include_hash_in_name is false. Expected object key {} would not match the advertised AutoHost path.",
					objectKey
			);
			return false;
		}

		String autoHostAddress = configuredAutoHostAddress();
		if (autoHostAddress != null && !normalizeBaseUrl(autoHostAddress).equals(normalizeBaseUrl(config.publicBaseUrl()))) {
			Rpr2uploader.LOGGER.error(
					"Refusing R2 upload because Polymer AutoHost settings.address ({}) does not match rp-r2-uploader publicBaseUrl ({}). The advertised URL would not match the uploaded R2 object.",
					autoHostAddress,
					config.publicBaseUrl()
			);
			return false;
		}

		Rpr2uploader.LOGGER.info(
				"Verified Polymer AutoHost path and R2 object key match: path={}, key={}, url={}",
				objectKey,
				objectKey,
				publicUrl
		);
		return true;
	}

	private static String configuredAutoHostAddress() {
		JsonElement providerSettings = AutoHost.config.providerSettings;
		if (providerSettings == null || !providerSettings.isJsonObject()) {
			return null;
		}

		JsonElement address = providerSettings.getAsJsonObject().get("address");
		return address == null || address.isJsonNull() ? null : address.getAsString();
	}

	static String normalizeBaseUrl(String url) {
		String normalized = url.trim();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private static String hexDigest(String algorithm, byte[] bytes) throws Exception {
		MessageDigest digest = MessageDigest.getInstance(algorithm);
		return HexFormat.of().formatHex(digest.digest(bytes));
	}

	static final record UploadResult(String objectKey, String publicUrl, String sha1, int size, String contentType) {
	}

	static final record PreparedUpload(
			Path packPath,
			byte[] packBytes,
			String sha1,
			String payloadSha256,
			String objectKey,
			URI objectUri,
			String publicUrl
	) {
	}

	static final record R2UploadConfig(
			String accountId,
			String accessKeyId,
			String secretAccessKey,
			String bucket,
			String publicBaseUrl
	) {
		boolean isComplete() {
			return accountId != null
					&& accessKeyId != null
					&& secretAccessKey != null
					&& bucket != null
					&& publicBaseUrl != null;
		}

		String missingMessage() {
			StringBuilder missing = new StringBuilder();
			appendMissing(missing, "accountId", accountId);
			appendMissing(missing, "accessKeyId", accessKeyId);
			appendMissing(missing, "secretAccessKey", secretAccessKey);
			appendMissing(missing, "bucket", bucket);
			appendMissing(missing, "publicBaseUrl", publicBaseUrl);
			return "missing " + missing;
		}

		private static void appendMissing(StringBuilder builder, String name, String value) {
			if (value != null) {
				return;
			}
			if (!builder.isEmpty()) {
				builder.append(", ");
			}
			builder.append(name);
		}

		URI objectUri(String objectKey) {
			String endpoint = "https://" + accountId + ".r2.cloudflarestorage.com";
			return URI.create(endpoint + "/" + encodePath(bucket) + "/" + encodePath(objectKey));
		}

		String publicUrl(String objectKey) {
			String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
			return base + "/" + objectKey;
		}
	}

	private static final class R2SignedUploader {
		private final HttpClient client;
		private final Clock clock;

		private R2SignedUploader(HttpClient client, Clock clock) {
			this.client = client;
			this.clock = clock;
		}

		private void putObject(R2UploadConfig config, String objectKey, URI uri, byte[] body, String payloadSha256)
				throws Exception {
			String amzDate = AMZ_DATE_FORMAT.format(clock.instant());
			String dateStamp = DATE_STAMP_FORMAT.format(clock.instant());
			String host = Objects.requireNonNull(uri.getHost(), "R2 endpoint host");
			String canonicalUri = "/" + encodePath(config.bucket()) + "/" + encodePath(objectKey);
			String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
			String canonicalHeaders = ""
					+ "content-type:" + CONTENT_TYPE + "\n"
					+ "host:" + host + "\n"
					+ "x-amz-content-sha256:" + payloadSha256 + "\n"
					+ "x-amz-date:" + amzDate + "\n";
			String canonicalRequest = ""
					+ "PUT\n"
					+ canonicalUri + "\n"
					+ "\n"
					+ canonicalHeaders + "\n"
					+ signedHeaders + "\n"
					+ payloadSha256;
			String credentialScope = dateStamp + "/" + R2_REGION + "/" + R2_SERVICE + "/aws4_request";
			String stringToSign = ""
					+ "AWS4-HMAC-SHA256\n"
					+ amzDate + "\n"
					+ credentialScope + "\n"
					+ hexDigest("SHA-256", canonicalRequest.getBytes(StandardCharsets.UTF_8));
			String signature = HexFormat.of().formatHex(hmac(signingKey(config.secretAccessKey(), dateStamp), stringToSign));
			String authorization = "AWS4-HMAC-SHA256 "
					+ "Credential=" + config.accessKeyId() + "/" + credentialScope + ", "
					+ "SignedHeaders=" + signedHeaders + ", "
					+ "Signature=" + signature;

			HttpRequest request = HttpRequest.newBuilder(uri)
					.header("Authorization", authorization)
					.header("Content-Type", CONTENT_TYPE)
					.header("x-amz-content-sha256", payloadSha256)
					.header("x-amz-date", amzDate)
					.PUT(HttpRequest.BodyPublishers.ofByteArray(body))
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("R2 PUT failed with HTTP " + response.statusCode() + ": " + response.body());
			}
		}

		private static byte[] signingKey(String secretAccessKey, String dateStamp) throws Exception {
			byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
			byte[] regionKey = hmac(dateKey, R2_REGION);
			byte[] serviceKey = hmac(regionKey, R2_SERVICE);
			return hmac(serviceKey, "aws4_request");
		}

		private static byte[] hmac(byte[] key, String data) throws Exception {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String encodePath(String path) {
		StringBuilder builder = new StringBuilder(path.length());
		String[] parts = path.split("/", -1);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				builder.append('/');
			}
			builder.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
		}
		return builder.toString();
	}
}
