# rp-r2-uploader

Fabric 26.3 mod that uploads Polymer's generated server resource pack and optional activity packs to Cloudflare R2 and lets Polymer AutoHost advertise the public R2 URL through the built-in `polymer:external` provider.

No custom AutoHost provider is added. AutoHost only sends the external URL; this mod performs the upload when Polymer finishes generating the pack.

## Versions

- Minecraft: `26.3`
- Java: `25`
- Gradle: `9.7.1`
- Fabric Loom: `1.18.2` (`net.fabricmc.fabric-loom`)
- Fabric Loader: `0.19.5`
- Fabric API: `0.161.0+26.3`
- Polymer AutoHost: `0.18.2+26.3`

Minecraft 26.1 and newer are unobfuscated. This branch follows the [Fabric porting guide](https://docs.fabricmc.net/26.1.2/develop/porting/): no mappings dependency or remapping task is used; `jar` produces the distributable mod.

## AutoHost Config

Example config is in `config/polymer/auto-host.json`.

For a real server, set `settings.address` to the same value as `publicBaseUrl` in `config/rp-r2-uploader.json`:

```json
{
	"enabled": true,
	"type": "polymer:external",
	"settings": {
		"address": "https://packs.example.com"
	},
	"include_hash_in_name": true
}
```

Polymer's Java config field is `providerSettings`; in `auto-host.json` it is serialized as `settings`.

Do not add a trailing slash to `settings.address`. Polymer's external provider appends `/<path>`.

With `include_hash_in_name: true`, Polymer advertises the main pack as:

```text
<settings.address>/polymer/resources+<sha1>.zip
```

The uploader writes the R2 object with the exact key:

```text
polymer/resources+<sha1>.zip
```

The key prefix is derived from Polymer AutoHost's default pack id path API, `AutoHostUtils.getPathFromId(AutoHostUtils.DEFAULT_PACK_ID)`.

That keeps Cloudflare cache behavior content-addressed instead of overwriting a stable filename.

## R2 Uploader Config

Put Cloudflare R2 credentials in the server-local mod config:

```text
config/rp-r2-uploader.json
```

Use `config/rp-r2-uploader.example.json` as the template:

```json
{
  "accountId": "your_cloudflare_account_id",
  "accessKeyId": "your_r2_access_key_id",
  "secretAccessKey": "your_r2_secret_access_key",
  "bucket": "your_bucket_name",
  "publicBaseUrl": "https://packs.example.com"
}
```

`publicBaseUrl` must be the public bucket URL or custom domain base URL that Minecraft clients can reach. It must match Polymer AutoHost's `settings.address`.

If `config/rp-r2-uploader.json` is missing, the mod creates a placeholder file and skips upload until you fill it in.

Do not commit `config/rp-r2-uploader.json`; it is ignored by `.gitignore`.

The uploader uses Cloudflare R2's S3-compatible API with Apache HttpClient and AWS Signature V4. If one DNS address cannot connect within 5 seconds, it tries the remaining addresses. TLS certificate and hostname verification remain enabled; no IP address is pinned. Response reads time out after 120 seconds of inactivity. HTTP errors are not retried, and redirects are not followed with signed credentials. It sends:

```text
Content-Type: application/zip
```

## Manual Upload Task

After a pack exists at `run/polymer/resource_pack.zip`, upload it without starting the server:

```bash
./gradlew uploadR2ResourcePack --console=plain
```

By default this task reads `run/config/rp-r2-uploader.json`. Use `-Pr2ConfigPath=...` to point at another local config:

```bash
./gradlew uploadR2ResourcePack --console=plain -Pr2ConfigPath=/absolute/path/to/rp-r2-uploader.json
```

To upload a different zip:

```bash
./gradlew uploadR2ResourcePack --console=plain -PpackPath=/absolute/path/to/resource_pack.zip
```

The task uses the same signing and upload code as the server event hook. It prints the R2 object key, expected public URL, SHA-1, size, and content type.

## Additional activity packs

The main pack keeps its existing `polymer/resources+<sha1>.zip` key. Additional packs use a separate namespaced ID and the same R2 account/bucket:

```text
destiny:exodus_crash -> destiny/exodus_crash+<sha1>.zip
destiny:devils_lair  -> destiny/devils_lair+<sha1>.zip
```

IDs must include a namespace. Duplicate IDs, the reserved main ID `polymer:resources`, and empty or traversal path segments are rejected. The SHA-1 is calculated from the actual uploaded bytes, so changed content gets a new URL without overwriting an old version.

To upload one additional ZIP manually:

```bash
./gradlew uploadR2ResourcePack --console=plain \
  -PpackId=destiny:exodus_crash \
  -PpackPath=/absolute/path/to/exodus_crash.zip
```

For automatic uploads after Polymer finishes generating its main ZIP, add `additionalPacks` to the existing local credential config:

```json
"additionalPacks": [
  { "id": "destiny:exodus_crash", "path": "packs/exodus_crash.zip" },
  { "id": "destiny:devils_lair", "path": "packs/devils_lair.zip" }
]
```

Relative paths are resolved against the server process working directory. The uploader does not create or split these ZIPs: they must already exist before Polymer's completion event. An empty or omitted list preserves the previous behavior. If startup restores a cached main pack without a generation event, this hook does not run; use the manual task or the API for newly generated activity packs.

Upload the main ZIP plus all configured additional packs in one command:

```bash
./gradlew uploadR2ResourcePacks --console=plain \
  -Pr2ConfigPath=/absolute/path/to/rp-r2-uploader.json \
  -PpackPath=/absolute/path/to/resource_pack.zip
```

The batch task checks that all files exist before starting, uploads them sequentially, prints each successful pack's URL/SHA-1/size, and fails on the first upload error. It is not an atomic transaction: already uploaded content-addressed objects remain if a later upload fails. The server completion hook logs additional-pack failures separately and continues with the remaining packs.

### Calling from another server mod

After generating an activity pack, call the public uploader API instead of implementing another R2 client:

```java
var published = R2ResourcePackUpload.uploadPack(
    "destiny:exodus_crash", Path.of("packs/exodus_crash.zip"));
// Success only: published.publicUrl(), published.sha1(), published.size()
```

This is a blocking network call; invoke it from the pack preparation worker, away from the server tick thread. It loads the existing local R2 config and throws on failure. The caller must not announce a URL or start the activity before the upload succeeds. Dispatch player pack packets back on the server thread and wait for the client's successful load acknowledgement before starting the activity. This API uploads bytes only: activity entry/exit, ZIP splitting, and client pack application are owned by the activity system and are not implemented by this uploader.

## Runtime Flow

1. Polymer AutoHost generates the resource pack.
2. `PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT` fires.
3. This mod reads the ZIP path from the event's `OutputGenerator.Result`; non-ZIP output is ignored.
4. The zip SHA-1 is calculated.
5. The zip is uploaded to `polymer/resources+<sha1>.zip` in R2. Configured additional ZIPs are then uploaded under their own IDs.
6. Polymer `polymer:external` sends `<publicBaseUrl>/polymer/resources+<sha1>.zip` to Minecraft clients.

If upload fails, the server log records an error saying AutoHost may advertise an unavailable URL. Missing config values are logged as a warning and upload is skipped.

## Verification

Build:

```bash
./gradlew build --console=plain
```

The build runs `verifyR2Uploader`, which checks the Polymer AutoHost key/URL format, ZIP completion handling, DNS failover, signed request body, HTTP errors, and redirect handling. The distributable is `build/libs/rp-r2-uploader-1.1.0+26.3.jar`.

Run a server and watch for both Polymer generation and R2 upload logs:

```bash
./gradlew runServer --console=plain
```

Expected upload log shape:

```text
Uploading Polymer resource pack ... to R2 key polymer/resources+<sha1>.zip. Expected AutoHost external URL: https://packs.example.com/polymer/resources+<sha1>.zip
Uploaded Polymer resource pack to R2: key=polymer/resources+<sha1>.zip, size=..., sha1=..., contentType=application/zip
```

Check the public URL:

```bash
curl -I "https://packs.example.com/polymer/resources+<sha1>.zip"
```

Expected:

```text
HTTP/2 200
content-type: application/zip
```

If using the manual task, copy the printed `Expected public URL` into the `curl -I` command.

Join the server with a Minecraft client and confirm the resource pack prompt/download URL points at the same R2 base URL.

## Local Secret Hygiene

`.gitignore` excludes `.env` files, `config/rp-r2-uploader.json`, and `config/polymer/auto-host.local.json`. Keep real credentials and private deployment values out of committed files.

`config/polymer/auto-host.json` is an example and uses a placeholder public URL.
