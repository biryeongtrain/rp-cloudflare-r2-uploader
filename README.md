# rp-r2-uploader

Fabric 1.21.8 template mod that uploads Polymer's generated server resource pack to Cloudflare R2 and lets Polymer AutoHost advertise the public R2 URL through the built-in `polymer:external` provider.

No custom AutoHost provider is added. AutoHost only sends the external URL; this mod performs the upload when Polymer finishes generating the pack.

## Versions

- Minecraft: `1.21.8`
- Fabric Loader: `0.19.2`
- Fabric API: `0.136.1+1.21.8`
- Polymer AutoHost: `0.13.13+1.21.8`

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

The uploader uses Cloudflare R2's S3-compatible API directly with Java `HttpClient` and AWS Signature V4. It sends:

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

## Runtime Flow

1. Polymer AutoHost generates the resource pack.
2. `PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT` fires.
3. This mod reads `PolymerResourcePackUtils.getMainPath()`.
4. The zip SHA-1 is calculated.
5. The zip is uploaded to `polymer/resources+<sha1>.zip` in R2.
6. Polymer `polymer:external` sends `<publicBaseUrl>/polymer/resources+<sha1>.zip` to Minecraft clients.

If upload fails, the server log records an error saying AutoHost may advertise an unavailable URL. Missing config values are logged as a warning and upload is skipped.

## Verification

Build:

```bash
./gradlew build --console=plain
```

The build runs `verifyR2Uploader`, which checks that the R2 key format matches Polymer AutoHost's hashed main-pack path.

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
