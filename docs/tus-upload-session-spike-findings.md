# Spike findings — `TusUploadSession` conformance report

**Status:** pre-implementation protocol research (no application code written).
**Targets:** (1) Supabase Storage `POST /storage/v1/upload/resumable` (tusd-style, `@tus/server` based), (2) Storyteller `POST /api/v2/books/upload` (`@tus/server` based).
**Question under test:** can one minimal TUS client (`POST create → Location`, `HEAD → Upload-Offset`, `PATCH chunk @ offset`, resume-after-process-death from persisted URL + offset) serve both backends?

**Headline answer: yes.** Both endpoints are the same protocol engine (`@tus/server` / tus-node-server) speaking **TUS 1.0.0 only**. The differences are in *metadata vocabulary*, *auth wrapper*, *session lifetime*, and *post-completion hooks* — not in the wire protocol. Three claims in the current planning doc are **wrong** and are corrected below (marked ⚠ CONFLICT).

---

## 0. Sources

| ID | Source |
|----|--------|
| [S1] | Supabase Docs — Resumable Uploads: https://supabase.com/docs/guides/storage/uploads/resumable-uploads |
| [S2] | `supabase/storage@master` — `src/http/routes/tus/index.ts` (routes, `ServerOptions`, `maxSize`, `allowedHeaders`, `disableTerminationForFinishedUploads`) |
| [S3] | `supabase/storage@master` — `src/http/routes/tus/lifecycle.ts` (`namingFunction`, `generateUrl`, `getFileIdFromRequest`, `onIncomingRequest`, `onCreate`, `onUploadFinish`) |
| [S4] | `supabase/storage@master` — `src/storage/protocols/tus/upload-id.ts` (upload id / Location encoding) |
| [S5] | `supabase/storage@master` — `src/config.ts` (`TUS_URL_PATH`, `TUS_URL_EXPIRY_MS`, `TUS_PART_SIZE`, `UPLOAD_FILE_SIZE_LIMIT`) |
| [S6] | `supabase/storage@master` — `src/storage/limits.ts` (`getFileSizeLimit`, key/bucket charset rules) |
| [S7] | `supabase/storage@master` — `src/storage/uploader.ts` (`canUpload` = RLS dry-run, `completeUpload`) |
| [S8] | `supabase/storage@master` — `src/storage/backend/adapter.ts` (`ObjectMetadata {size, contentLength, mimetype, eTag, …}`) |
| [S9] | `supabase/storage@master` — `acceptance/specs/tus.test.ts` (live contract tests: resume, 409, HEAD 404/410, DELETE, signed flow) |
| [S10] | `supabase/storage@master` — `package.json` (`@tus/server 2.4.2`, `@tus/s3-store 2.0.3`, `@tus/file-store 2.1.0`) |
| [S11] | `supabase/storage@master` — `src/http/plugins/jwt.ts`, `src/http/plugins/apikey.ts` |
| [S12] | `supabase/storage@master` — `src/storage/protocols/tus/file-store.ts` (self-hosted store) |
| [T1] | TUS protocol v1.0.0: https://tus.io/protocols/resumable-upload.html |
| [T2] | `tus/tus-node-server@main` — `packages/server/src/server.ts`, `handlers/{Post,Patch,Head,Delete,Options}Handler.ts`, `validators/HeaderValidator.ts`, `packages/utils/src/constants.ts`, `packages/utils/src/models/Metadata.ts`, `packages/file-store/src/index.ts`, `packages/s3-store/src/index.ts` (behaviour of the engine pinned by both servers) |
| [ST1] | `storyteller-platform/storyteller` (GitLab, ref=HEAD) — `applications/web/src/app/api/v2/books/upload/[[...path]]/route.ts` |
| [ST2] | … `applications/web/src/app/api/v2/books/upload/finalize/route.ts` |
| [ST3] | … `applications/web/src/app/api/v2/books/route.ts` (POST import) |
| [ST4] | … `applications/web/src/app/api/v2/books/[bookId]/process/route.ts` |
| [ST5] | … `applications/web/src/app/api/v2/books/[bookId]/replace-asset/upload/[[...path]]/route.ts` |
| [ST6] | … `applications/web/src/app/(v3)/v3/_/components/files/{useTusUpload,UploadDialog}.tsx`, `…/books/UploadBookDialog.tsx`, `applications/web/src/components/books/modals/UploadBooksModal.tsx` (first-party client contracts) |
| [ST7] | … `applications/web/src/auth/auth.ts`, `applications/web/src/app/api/v2/token/route.ts` |
| [ST8] | … `applications/web/src/envSchema.ts`, `…/api/v2/settings/maxUploadChunkSize/route.ts`, `…/settings-form/upload-tab.tsx`, `applications/web/src/database/settingsTypes.ts`, `applications/web/src/directories.ts` |
| [ST9] | … `applications/web/package.json` (`@tus/server ^2.2.0`, `@tus/file-store ^2.0.0`, `@uppy/tus ^4.2.2`) |
| [ST10] | Storyteller docs: https://storyteller-platform.dev/docs/installation/self-hosting/ , /docs/managing/adding/ ; v2 announcement (resumable uploads): https://smoores.dev/post/announcing_storyteller_v2/ |
| [D1] | Supabase Docs — Limits: https://supabase.com/docs/guides/storage/uploads/file-limits |
| [D2] | Supabase Blog — Storage v3 resumable uploads (50 GB): https://supabase.com/blog/storage-v3-resumable-uploads |
| [L1] | Local planning doc: `docs/parrot-cloud-book-file-transfer-implementation-plan.md` |

All GitHub citations refer to `supabase/storage` master and `tus/tus-node-server` main as fetched on the research date. Both servers pin released versions of `@tus/server` ([S10], [ST9]); the handler behaviour cited matches those majors (2.x), but the spike should confirm wire behaviour live (see §5, T0).

---

## 1. Supabase `/upload/resumable` protocol profile

Endpoint (docs advise the direct storage hostname for large files [S1]):
`https://<project-ref>.storage.supabase.co/storage/v1/upload/resumable` (path from `TUS_URL_PATH`, default `/upload/resumable` [S5]).

### 1.1 Creation (POST)

| Aspect | Finding | Source |
|---|---|---|
| Method/path | `POST /storage/v1/upload/resumable`, empty body (or first chunk — see creation-with-upload) | [S2], [T2] PostHandler |
| Required headers | `Tus-Resumable: 1.0.0`, `Upload-Length: <bytes>` | [T2] server.ts (missing `Tus-Resumable` → **412** `Tus-Resumable Required`) |
| Accepted `Tus-Resumable` versions | **`1.0.0` only** (`TUS_RESUMABLE = '1.0.0'`, `TUS_VERSION = ['1.0.0']`). Any other value (e.g. `0.1.0`) is rejected by header validation with **400** `Invalid tus-resumable` (note: spec asks for 412 on version mismatch — tus-node-server deviates; either way it is rejected) | [T2] constants.ts, HeaderValidator.ts, server.ts |
| `Upload-Metadata` encoding | TUS standard: comma-separated `key base64(value)` pairs, no spaces around commas; value = **standard base64 with padding** (parser requires `len % 4 == 0` and base64 charset); key = ASCII, no space/comma; empty value allowed as bare key | [T1] §Creation/Upload-Metadata; [T2] models/Metadata.ts `parse`/`stringify` |
| Supabase metadata keys | `bucketName` (required), `objectName` (required, may contain `/`), `contentType`, `cacheControl` (bare number is rewritten to `max-age=N`, else `no-cache`), `metadata` (JSON **string** → stored as `user_metadata`) | [S3] `namingFunction`, `onCreate`, `onIncomingRequest` |
| Response | `201 Created` + `Location` (+ `Upload-Offset` if creation-with-upload body present) | [T2] PostHandler; [S9] tests |
| `Location` shape | `https://<host>/upload/resumable/<base64url("bucket/objectName/<version-uuid>")>` — tenant is stripped before encoding; `version` = `randomUUID()` minted at creation | [S3] `generateUrl`/`getFileIdFromRequest`; [S4] `UploadId` |
| Location stability | **Stable and deterministic** (it *is* the encoded upload id, not a per-session handle). State lives in the S3 backend (`.info` object + multipart upload), so it survives storage-api restarts. The signed variant appends `/sign` to the base path | [S3]; [T2] s3-store `saveMetadata`/`getMetadata` |
| Creation-with-upload | Supported (`creation-with-upload` in `Tus-Extension`): send `Content-Type: application/offset+octet-stream` + first bytes in POST body; response `201` + `Upload-Offset` | [T2] s3-store extensions, PostHandler; [S9] "accepts upload data during creation" |
| Max file size | Enforced at creation: bucket `file_size_limit` capped by tenant global limit → **413** `Maximum size exceeded` (a `Tus-Max-Size` value is also exposed on OPTIONS via the same `maxSize` callback) | [S2] `maxSize`; [S6]; [S9] "rejects uploads exceeding the bucket file size limit" |
| Required auth | `Authorization: Bearer <JWT>` (see §1.5). On hosted Supabase also send `apikey: <anon/publishable key>` (gateway requirement; one docs example omits it, the Uppy example includes it) | [S2] `registerJwtAuth`; [S1] |

### 1.2 PATCH semantics

| Aspect | Finding | Source |
|---|---|---|
| Request | `PATCH <Location>`, `Upload-Offset: <server offset>`, `Content-Type: application/offset+octet-stream`, `Tus-Resumable: 1.0.0` | [T1] §PATCH |
| Content-Type wrong/missing | **403** `Content-Type header required` in tus-node-server (spec says 415 — another deviation to tolerate client-side) | [T2] ERRORS.INVALID_CONTENT_TYPE, PatchHandler |
| Offset mismatch | **409** `Upload-Offset conflict`, upload **not** modified. Recovery: `HEAD` → take returned `Upload-Offset` → re-PATCH from there (proven by acceptance test which asserts the offset is unchanged after 409) | [T2] PatchHandler/ERRORS.INVALID_OFFSET; [S9] "rejects PATCH requests whose Upload-Offset does not match" |
| Success | `204 No Content` + `Upload-Offset: <new offset>` per chunk → per-chunk progress for free | [T1]; [T2] |
| Max chunk size | **No per-PATCH cap in the server.** Internal S3 part size is `TUS_PART_SIZE` (default 50 MB) — that is an implementation detail, not a request limit ([S5], [S2]). ⚠ CONFLICT: docs say client `chunkSize` "must be set to 6MB (for now)" [S1] — this is *client guidance* (likely for the API gateway path), not a protocol rule. Spike must measure whether >6 MB PATCHes pass through the hosted gateway |
| Mid-chunk network failure | Server stores as much as received ([T1] "SHOULD always attempt to store as much…"); client must re-`HEAD` and continue — never assume the whole chunk landed | [T1] |
| Final response | `204` + `Upload-Offset == Upload-Length`, **plus Supabase-specific `Tus-Complete: 1`** response header from `onUploadFinish`. No body; the storage path is *not* returned — it is the client-chosen `objectName` from creation metadata | [S3] `onUploadFinish` returns `{headers: {'Tus-Complete': '1'}}`; [T2] PatchHandler |
| Concurrency | Per-upload mutex (PgLocker default, or S3Locker) with lock-drain abort; two clients on the same upload URL → one wins, other gets 409/400. Two *different* uploads to the same `objectName`: first to complete wins, the other 409s at completion — unless `x-upsert: true`, in which case the last completion wins | [S2] `locker`; [S1] "Concurrency"; [S7] `completeUpload` `KeyAlreadyExists` |

### 1.3 HEAD (offset discovery after process death)

- Supported. Returns `200` + `Upload-Offset` (always, even 0), `Upload-Length`, `Upload-Metadata` (echoed from creation), `Cache-Control: no-store` [T2] HeadHandler; [S9] `getTusOffset`.
- Unknown/expired upload → `404`/`410` (acceptance-tested) [S9] "returns 404 or 410 for HEAD on a non-existent upload resource".
- **Auth-free on Supabase** — `OPTIONS` and `HEAD` skip JWT verification ([S3] `onIncomingRequest`: *"Options and HEAD request don't need to be authorized"*). You can re-sync offsets before you have refreshed the JWT.

### 1.4 Session lifetime / expiry

- The `expiration` extension **is** advertised (both S3Store and FileStore list `creation, creation-with-upload, creation-defer-length, termination, expiration`) [T2] s3-store/file-store constructors.
- Expiry period = `TUS_URL_EXPIRY_MS`, **code default 3 600 000 ms = 1 hour** ([S5]).
- ⚠ CONFLICT: Supabase docs say the upload URL "will be valid for **up to 24 hours**" [S1]. The open-source default is 1 h; hosted presumably sets 24 h. **Do not guess** — the server tells you: `Upload-Expires` is emitted on the POST response (when incomplete) and on **every incomplete PATCH response** ([T2] PostHandler/PatchHandler). Persist `Upload-Expires` alongside the URL; treat past-expiry as "recreate".
- After expiry: `HEAD`/`PATCH` → **410** `The file for this url no longer exists` [T2] HeadHandler/PatchHandler (`FILE_NO_LONGER_EXISTS`). Client policy: 404/410 ⇒ create a new upload (spec's own advice [T1] §Expiration).
- Abandoned uploads are garbage-collected server-side (S3 lifecycle driven by `Tus-Completed` object tags when expiry + tags are enabled [T2] s3-store `shouldUseExpirationTags`). Nothing is required from the client.
- What invalidates a session: expiry window elapsed, explicit `DELETE`, or (for completion races) another client finishing the same `objectName` first. **JWT expiry does not invalidate the upload URL** — auth is checked per request, not baked into the URL.

### 1.5 Auth model & RLS

- Every non-OPTIONS/non-HEAD request needs `Authorization: Bearer <JWT>` — user access token from supabase-kt auth (docs' own examples use `session.access_token`) [S1]; JWT is verified against the project secret/JWKS, `owner = payload.sub` [S11] jwt.ts.
- Hosted gateway additionally wants `apikey: <anon key>` (docs' Uppy sample) [S1].
- **Signed/anonymous tokens:** signed upload URLs work — `POST /object/upload/sign/{bucket}/{objectName}` (authenticated) returns `{url, token}`; then use TUS at `/upload/resumable/sign` with header `x-signature: <token>` and **no JWT** [S1] "Presigned uploads"; [S3] `SIGNED_URL_SUFFIX` + `verifyObjectSignature`; [S9] signed-flow test. Token validity is time-limited (config `UPLOAD_SIGNED_URL_EXPIRATION_TIME`, default `60` — units unverified, see §4).
- A bare anon-role JWT is accepted by the TUS routes (no `enforceJwtRoles` on them [S2][S11]); whether it can actually write is then purely an RLS question.
- **RLS/storage policies apply to resumable uploads, per request.** `onIncomingRequest` runs `uploader.canUpload(...)` for POST **and for every PATCH/DELETE**, performing a policy-checked dry-run `createObject`/`upsertObject` (as the JWT role) with `owner`, mimetype and content-length [S3] `onIncomingRequest` (PATCH branch re-reads the stored upload's metadata), [S7] `canUpload` → `db.testPermission`. So a `users/{uid}/…` policy with `(storage.foldername(name))[2] = auth.uid()::text` is enforced from creation through the last chunk. Consequence: **resuming under a different user than the one that created the upload fails** — the persisted URL survives, the *credentials* must be refreshed for the same user.

### 1.6 Checksum / integrity

- **`Upload-Checksum` is NOT supported.** The checksum extension is absent from `Tus-Extension` (store extension lists have no `checksum` [T2] s3-store/file-store), `Upload-Checksum` is not among validated/known headers and is silently ignored by `PatchHandler` [T2] HeaderValidator (unknown headers pass), PatchHandler.
- Sending it is harmless but useless. **Client-side hashing is the only option.**
- Useful hooks: (a) put a client-computed `sha256` into the creation `metadata` JSON (`user_metadata`) — stored verbatim at completion [S3] `onCreate`, [S7]; (b) compare local hash with the hash of the downloaded object after completion (download via the authenticated object endpoint) — the acceptance suite itself verifies round-trip integrity this way [S9]; (c) byte-count check via final `Upload-Offset == Upload-Length` and `storage.objects.metadata->>'size'` (§1.7).

### 1.7 Cancellation & object materialization

- `DELETE` (termination extension) is routed and **works for unfinished uploads**: `204`, then `HEAD` → 404/410 (acceptance-tested) [S2] `TUS_DELETE_UPLOAD`; [S9] "terminates abandoned uploads through DELETE".
- For **finished** uploads termination is disabled (`disableTerminationForFinishedUploads: true`) → `400 Cannot terminate an already completed upload` [S2]; [T2] DeleteHandler. Deleting the resulting object goes through the normal object-delete API instead.
- **The storage object materializes only at completion.** Until the final byte, there is only an S3 multipart upload + `.info` sidecar. `onUploadFinish` → `Uploader.completeUpload` performs the transactional `storage.objects` upsert (and fires `OBJECT_CREATED_*` webhooks) [S3] `onUploadFinish`; [S7] `completeUpload`.
- Object metadata written at completion is `ObjectMetadata { cacheControl, contentLength, size, mimetype, eTag, … }` [S8] — so after completion `select metadata->>'size' from storage.objects where bucket_id='book-files' and name='users/{uid}/books/{bookId}/{fileId}'` is valid and equals `Upload-Length` [S7] `completeUpload`(`objectMetadata: metadata` from `headObject`).
- Size limits: global limit per plan — **Free ≤ 50 MB (hard)**, Pro/Team configurable up to **500 GB** (per-bucket `file_size_limit` may be lower) [D1]; resumable/S3 path historically raised from 5 GB → 50 GB [D2] → 500 GB [D1]. For a ≥100 MB test file the spike project **must be Pro**.
- Object name charset is restricted (S3-safe set; no `{}` etc.) [S6] `VALID_OBJECT_KEY` — `users/{uid}/books/{bookId}/{fileId}` is fine as long as segments use the safe charset.

---

## 2. Storyteller `/api/v2/books/upload` protocol profile

Server = Next.js route handler wrapping `@tus/server` + `@tus/file-store`, uploads land in `{DATA_DIR}/uploads` (`/data/uploads` in the container) [ST1], [ST8] `directories.ts`, [ST9]. GitLab: `storyteller-platform/storyteller` (public monorepo).

### 2.1 Creation (POST)

| Aspect | Finding | Source |
|---|---|---|
| Method/path | `POST /api/v2/books/upload` | [ST1] |
| Required headers | `Tus-Resumable: 1.0.0`, `Upload-Length` (or `Upload-Defer-Length: 1` — FileStore advertises `creation-defer-length`) | [ST1]; [T2] file-store extensions |
| Accepted versions | **`1.0.0` only** — same `@tus/utils` constants. Missing → 412, `0.1.0` → 400 `Invalid tus-resumable`. ⚠ CONFLICT: any community client sending `Tus-Resumable: 0.1.0` (as referenced in planning discussions) **cannot work against either backend**; send `1.0.0` | [ST9]; [T2] constants.ts/HeaderValidator |
| `Upload-Metadata` encoding | Identical TUS base64-pair format ([T2] models/Metadata.ts — comma-separated, `key value` space-separated, padded base64) | [T2] |
| Metadata keys read by `/books/upload` | **`bookUuid`** (required, UUID v4 — see §2.6), **`filename`** (required), **`filetype`** (optional MIME; else inferred from filename), **`collection`** (optional collection UUID), **`totalFiles`** (optional batch counter, §2.6). First-party v3 client additionally sends `batchId` [ST6]. ⚠ CONFLICT: **`relativePath` is NOT read by this route** — it exists only in the `replace-asset` upload route (`/api/v2/books/{id}/replace-asset/upload`), where it is optional and used for batch directory layout [ST5]. Sending `relativePath` to `/books/upload` is a no-op | [ST1] `onUploadFinish`; [ST5]; [ST6] `UploadBookDialog.buildMeta` |
| `Location` | `<origin>/api/v2/books/upload/<id>` where `id = randomBytes(16).toString("hex") + ext(filename)`; `respectForwardedHeaders: true` so proxy prefixes are honored | [ST1] `namingFunction`; [T2] BaseHandler default `generateUrl` |
| Location stability | **Stable across restarts**: state = the partial file + `<id>.json` configstore sidecar on disk under `/data/uploads` — no in-memory-only state | [ST1]; [T2] file-store/FileConfigstore |
| Creation-with-upload | Supported (FileStore advertises `creation-with-upload`) | [T2] file-store |
| Max size | **No `maxSize` configured** → no `Tus-Max-Size`, no 413 size limit; disk space is the only bound | [ST1] Server options |
| Metadata validation timing | ⚠ All semantic validation (required keys, file type) happens in **`onUploadFinish`, i.e. after 100 % of bytes are uploaded** — a bad request fails with `405` + explanatory body on the *final* PATCH (e.g. `Missing required metadata: bookUuid`). There is no `onUploadCreate` hook. Client must validate its own metadata before starting | [ST1] `onUploadFinish` |

### 2.2 PATCH semantics

- Same engine as Supabase: `Content-Type: application/offset+octet-stream` required (403 if missing), `Upload-Offset` must match or **409** `Upload-Offset conflict`, `204` + new `Upload-Offset` per chunk, no per-PATCH size cap [T2] PatchHandler.
- **`STORYTELLER_MAX_UPLOAD_CHUNK_SIZE` is advisory, not enforced.** ⚠ CONFLICT with the planning doc's "server config … default 10 MB / hard reject": the value is only served to clients via `GET /api/v2/settings/maxUploadChunkSize` → `{ maxUploadChunkSize, overriden }`, and the first-party web client feeds it to Uppy's `chunkSize` [ST8] route; [ST6] `useTusUpload.startUpload`. There is **no server-side rejection of larger PATCHes**. Additional unit ambiguity: the env var is documented as *"megabytes | 10 (10 MB)"* but has **no zod default** (`optional()`), the DB setting default is **`null` (= no limit)**, and the value is consumed by `tus-js-client` as **bytes** (the settings UI seeds `100_000_000` = 100 MB bytes) [ST8] envSchema, settingsTypes.ts (`maxUploadChunkSize: null`), upload-tab.tsx. Treat the returned number as bytes and verify live (§5, T7).
- Final PATCH: `204`, `Upload-Offset == Upload-Length`. The hook then moves the file to `uploads/{bookUuid}/{filename}` and kicks the library scanner; its response carries no extra headers (unlike Supabase's `Tus-Complete`) [ST1]; [T2] PatchHandler.

### 2.3 HEAD

- Supported (`HEAD` is exported and routed like all other methods) — returns `Upload-Offset` (== current file size), `Upload-Length`, `Upload-Metadata` echo, `Cache-Control: no-store` [ST1]; [T2] HeadHandler.
- **Requires auth** (`withHasPermission("bookCreate")` wraps HEAD too) — unlike Supabase [ST1].

### 2.4 Session lifetime

- FileStore is constructed **without** `expirationPeriodInMilliseconds` → `getExpiration() = 0` → all expiry checks are skipped, `Upload-Expires` is never sent, `deleteExpired()` no-ops [ST1]; [T2] file-store (`expirationPeriodInMilliseconds ?? 0`; checks guarded by `getExpiration() > 0`).
- ⇒ **Upload sessions never expire server-side.** Persisted URL + `HEAD` resume works indefinitely (months later, across restarts/upgrades, since `/data` is a volume). Nothing sweeps `uploads/` — abandoned partials accumulate until manually cleaned (housekeeping risk, not correctness risk).
- What invalidates: manual `DELETE`, container `/data` wipe, or `onUploadFinish`'s cleanup removing the file+sidecar after completion (afterwards HEAD/PATCH/DELETE on that URL → 404) [ST1] `finally` block.

### 2.5 Auth model

- **Bearer session token** or **`st_token` cookie** (NextAuth session cookie, 30-day maxAge) — both accepted; the middleware transparently maps `Authorization: Bearer <token>` onto the `st_token` cookie before session resolution [ST7] `withHasPermission`/`withUser`.
- Token issuance for API clients: `POST /api/v2/token` with form data `usernameOrEmail`, `password` → `{ access_token, expires_in, token_type: "bearer" }` (the token is the session token; `expires_in` in ms) [ST7] token route, `createUserToken`. There is also a device-code flow (`/api/v2/device/*`, `/api/v2/token/app`) for TV/app clients [ST6 tree].
- Permission gates: uploads (all TUS methods incl. HEAD/OPTIONS) require **`bookCreate`**; `replace-asset` uploads require **`bookUpdate`**; `process` requires **`bookProcess`** [ST1], [ST5], [ST4]. Failure → `401 {"message":"Not authenticated"}` or `403 {"message":"Forbidden"}`.
- Basic auth exists only for OPDS routes (opt-in) — irrelevant here [ST7].

### 2.6 Book lifecycle around the upload

⚠ CONFLICT with the planning doc ("*the client must create a book first, then TUS files into it*"): **there is no pre-create call.** The client **generates a UUID v4 locally** and passes it as `Upload-Metadata: bookUuid` [ST6] `UploadBookDialog` (`bookUuidRef = useRef<UUID>(uuidv4())`). The book row is created *after the last file lands*, by the scanner (`scan({ bookUuidHint })`) triggered from `onUploadFinish` [ST1]. `POST /api/v2/books` is a different flow — "import from paths already on the server" (`{paths[], collection?, importMode, epub2Strategy}`) and is not used for uploads [ST3].

Multi-file audiobook flow ([ST1], [ST6]):

1. Client picks one `bookUuid` and sends each file (EPUB + N audio, or audio-only) as its own TUS upload with `filename` = **flat file name** (the server does `join(uploadsDir/bookUuid, filename)`; nested names are not prepared for) and `totalFiles = <batch size>` (v3 client) [ST1], [ST6].
2. `onUploadFinish` moves each file into `uploads/{bookUuid}/`; when `totalFiles > 1` it counts arrived importable files and **defers the scan until all have landed** (in-memory guard set). Single files scan immediately.
3. `relativePath` semantics: **not applicable to this endpoint** (flat naming; track order derives from filenames in the shared folder). `relativePath` only shapes the *replace-asset* batch path (`uploads/<tmp>/<batchId>/<relativePath>`) [ST5].
4. If some files fail permanently, `POST /api/v2/books/upload/finalize` with `{bookUuid, collectionUuid?}` force-scans whatever is present → `204` [ST2]. This is the documented "fallback for multi-file audiobook upload".
5. ⚠ Internal inconsistency in Storyteller itself: the legacy v2 modal sends `totalAudioFiles`, which `/books/upload` **ignores** (it reads `totalFiles`); `replace-asset` reads `totalAudioFiles` [ST6] `UploadBooksModal` vs [ST1] vs [ST5]. Send `totalFiles` to `/books/upload`; consider sending both keys for tolerance, and verify in the spike (T6).

`POST /api/v2/books/{uuid}/process` ([ST4]): enqueues alignment/processing (`enqueueBookAlign`), `204 No Content` on success. Query params: `restart=full|transcode|transcription|sync` (default: continue), `gpuWarning=check` → `{showWarning, …}` without processing, `gpuWarning=dismiss`. Body: `{config?: Partial<RunConfig>}`. **`409` if either the ebook or the audiobook is missing** (`Cannot process book: both ebook and audiobook must be present and not missing`) — i.e. processing requires a paired book. `DELETE /…/process` cancels (`204`). Progress is polled via `/api/v2/jobs` (and `/api/v2/events`), not from the process response.

Size limits: none per file or per book at the protocol layer [ST1] (no `maxSize`); chunk size advisory only (§2.2). Disk is the limit.

---

## 3. Conformance matrix — `TusUploadSession` behaviors

Legend: **SUPPORTED** / **PARTIAL** (adaptation required) / **UNSUPPORTED** (workaround).

| Required behavior | Supabase `/upload/resumable` | Storyteller `/api/v2/books/upload` |
|---|---|---|
| **Create** (`POST` → `Location`) | **SUPPORTED** — 201 + `Location` (base64url id). Metadata vocabulary = `bucketName`/`objectName`/`contentType`/`cacheControl`/`metadata` [S1][S3]. Adapter: map "target path" → these keys | **SUPPORTED** — 201 + `Location`. Metadata vocabulary = `bookUuid`/`filename`/`filetype`/`collection`/`totalFiles` [ST1]. Adapter: map "book batch" → these keys |
| **PATCH streaming w/ per-chunk progress** | **SUPPORTED** — 204 + `Upload-Offset` per chunk; recommend 6 MB chunks (docs) [S1][T2] | **SUPPORTED** — 204 + `Upload-Offset` per chunk; use `maxUploadChunkSize` from settings endpoint as chunk size (bytes; verify units) [ST6][ST8] |
| **HEAD-based offset recovery after process death** | **SUPPORTED** — 200 + `Upload-Offset`/`Upload-Length`/`Upload-Metadata`; even auth-free [S3][T2] | **SUPPORTED** — same headers; auth required [ST1][T2] |
| **Persisted URL + offset resume (later)** | **PARTIAL** — URL+state survive restarts, but sessions expire (`Upload-Expires`, code default 1 h, docs 24 h ⚠). Adaptation: persist `Upload-Expires`; on 404/410 re-create and re-upload; refresh the JWT (1 h user tokens) before resuming. HEAD works pre-auth [S1][S5][T2] | **SUPPORTED** — no server-side expiry at all; files on `/data` persist indefinitely. Only token may need refresh (30-day sessions) [ST1][ST7][T2] |
| **Per-chunk retry with backoff** | **SUPPORTED** — re-HEAD after failure; 409 → adopt server offset; lock-aborts (400) are retryable [S9][T2] | **SUPPORTED** — identical semantics [T2] |
| **Pause / cancel** | **PARTIAL** — pause = stop sending (resume via HEAD). Cancel = TUS `DELETE` **only while unfinished** (204; proven in acceptance); finished uploads reject DELETE (400). Else: abandon + server GC [S2][S9] | **SUPPORTED** — TUS `DELETE` works pre-completion (204); post-completion the URL is already consumed (404, harmless). Pause same as Supabase [ST1][T2] |
| **Byte-count verification** | **SUPPORTED** — final `Upload-Offset == Upload-Length`, `Tus-Complete: 1` on final PATCH, and `storage.objects.metadata->>'size'` after completion [S3][S7][S8] | **PARTIAL** — final `Upload-Offset == Upload-Length` and HEAD echo; no completion header and no server-side metadata echo of the stored size. Adaptation: verify by re-HEAD before completion-cleanup (or file size via `/api/v2/books/{id}/files`) [ST1][T2] |
| **Hash verification hook (TUS checksum or client-only)** | **UNSUPPORTED (client-only)** — no `checksum` extension; `Upload-Checksum` silently ignored [T2]. Workaround: client SHA-256 during read; optionally pre-store hash in `metadata` user_metadata; post-verify by downloading and hashing (as Supabase's own acceptance suite does) [S9] | **UNSUPPORTED (client-only)** — same engine, same gap [T2]. Workaround: client SHA-256; stash expected hash in `Upload-Metadata` (echoed by HEAD) for the resume-state record |
| **Concurrent-session safety** | **SUPPORTED** — per-upload Pg/S3 lock; same URL: one winner, other 409. Same object, different URLs: first completion wins, other 409 — `x-upsert: true` flips to last-wins [S1][S2][S7] | **PARTIAL** — per-upload lock is `MemoryLocker` (in-process default; Storyteller configures no locker [ST1][T2]) — safe on the single-node container, **unsafe if scaled to >1 process/replica**. Same-book batches are serialized via in-memory `inProgressBatchScans` set + `finalize` fallback [ST1][ST2]. Adaptation: single-flight per upload URL in the client; keep one upload per file |

Net: a single `TusUploadSession` core (create/patch/head/delete + offset state machine) covers everything; per-backend adapters supply (a) metadata vocabulary, (b) auth header injection + refresh, (c) expiry policy (persist `Upload-Expires` vs "never"), (d) completion detection (`Tus-Complete` vs offset==length + finalize/scan semantics).

---

## 4. Risks & unknowns (ranked) — what the spike must prove

1. **Real upload-URL expiry on hosted Supabase** (docs 24 h vs code default 1 h ⚠). *Prove:* record `Upload-Expires` from POST/PATCH and compute the window; confirm 410 after expiry with a short `TUS_URL_EXPIRY_MS` local run if needed. Client design hinges on "recreate after expiry" logic.
2. **iOS survival of the *resume state*** — process death on iOS (SIGKILL from Xcode / app switcher) vs Ktor client sockets; persisted URL+offset in `NSUserDefaults`/file must be intact and the resumed PATCH must continue at server offset. *Prove:* T3 on both OSes with kill at 30–50 %.
3. **Storyteller `maxUploadChunkSize` units & enforcement** (env "MB" vs Uppy bytes vs DB `null` default ⚠). *Prove:* T7 — GET the endpoint with env set, then send an over-limit PATCH and observe 204 (expected: no rejection).
4. **Storyteller late metadata validation** — failure surfaces only on the final PATCH after full transfer (405). *Prove:* T6-negative (create upload with missing `bookUuid`, upload 20 MB, observe 405 at the end) → design implication: client-side metadata validation is mandatory, and errors need "fatal, don't retry" classification for hook-returned status codes.
5. **Auth-token lifetime vs long uploads** — Supabase user JWTs (~1 h) and Storyteller sessions (30 d) are checked per request. *Prove:* T8 — expire/replace the token mid-upload and resume with a refreshed one (must continue on the same URL).
6. **Hosted gateway behavior for big chunks** — Supabase docs' "chunkSize must be 6 MB" ⚠ and Storyteller's reverse-proxy note ("robust to … opinionated reverse proxies" [ST10]); nginx `client_max_body_size` defaults can reject large PATCHes *before* the app. *Prove:* T1/T2 with 6 MB and 16 MB chunks through the real hostname; record which sizes pass.
7. **Wrong-version client compatibility** — community `Tus-Resumable: 0.1.0` gets 400 (not spec's 412) on both. *Prove:* T0 negative check so error handling maps 400/412 → "incompatible client".
8. **Storyteller batch-counter key mismatch** (`totalFiles` vs `totalAudioFiles`) ⚠. *Prove:* T6 multi-file audiobook with `totalFiles=N` — scan must fire exactly once after the Nth file.
9. **`Tus-Complete` header exposure** — non-CORS clients see it, but confirm it's not stripped by proxies. *Prove:* T1 capture raw response headers.
10. **Supabase Free-plan 50 MB cap** silently breaking >50 MB tests [D1]. *Prove:* run the big-file tests on a Pro project; also verify `metadata->>'size'` post-completion via SQL.
11. **Signed-URL token expiry units** (`UPLOAD_SIGNED_URL_EXPIRATION_TIME=60`, s vs min unknown) — only matters if the app ever uses presigned flows. Low priority.
12. **Storyteller orphan accumulation** in `/data/uploads` (no GC) — operational, not blocking.

---

## 5. Half-day live-spike runbook (real devices)

### 5.1 Prerequisites (T-30 min)

- [ ] **Supabase dev project on the Pro plan** (Free's 50 MB cap blocks the ≥100 MB test [D1]). Storage enabled.
- [ ] Create private bucket `book-files`; add storage policies allowing `insert`/`update`/`select` on `bucket_id='book-files'` for `auth.uid()`-owned paths (`users/{uid}/...`); create a test user (email+password) and note anon key + URL.
- [ ] **Storyteller instance:** `docker run -it --name storyteller -v ~/Documents/Storyteller:/data:rw -p 8001:8001 -e STORYTELLER_SECRET_KEY=$(openssl rand -base64 32) registry.gitlab.com/storyteller-platform/storyteller:latest` [ST10]; complete first-run admin setup; note `http://<lan-ip>:8001`.
- [ ] Test files on each device: one ~5 MB EPUB, 3 small MP3s, and **`big.bin` ≥ 100 MB** (`dd if=/dev/urandom of=big.bin bs=1m count=150`) — record local `shasum -a 256` of every file.
- [ ] Spike harness: minimal KMP screen wrapping `TusUploadSession` with start/pause/resume/kill-at-percentage + a raw request log (method, URL, req headers, status, res headers, bytes, ms). Laptop with `curl`+`jq`; optional mitmproxy. `adb` for Android, Xcode for iOS.
- [ ] T0 (10 min, laptop): `OPTIONS` both endpoints; record `Tus-Version`, `Tus-Extension`, `Tus-Max-Size`. Negative: repeat POST with `Tus-Resumable: 0.1.0` → expect rejection (record status/body).

### 5.2 Test script

Run T1–T8 per endpoint; **T3 twice — once on Android, once on iOS.**

**T1 — Create + single-chunk PATCH (both backends).**
Supabase: POST with `Upload-Length`, `Upload-Metadata: bucketName <b64 "book-files">,objectName <b64 "users/{uid}/books/{bookId}/{fileId}">,contentType <b64>,metadata <b64 "{\"sha256\":\"…\"}">`, `Authorization: Bearer <jwt>`, `apikey: <anon>`.
Storyteller: obtain token via `POST /api/v2/token` (form); POST with `Upload-Metadata: bookUuid <b64>,filename <b64 "big.bin">,filetype <b64 "application/octet-stream">,totalFiles <b64 "1">`.
Record: `Location` (decode the Supabase base64url tail — expect `bucket/objectName/uuid`), `Upload-Expires` if present (Supabase), `Tus-Extension`. PATCH the whole small file; record 204 + `Upload-Offset`, and on Supabase the final `Tus-Complete: 1`.

**T2 — Chunked PATCH + offset-mismatch recovery.** Upload `big.bin` in 6 MB chunks (then repeat with 16 MB chunks — answers risk #6). Deliberately send one PATCH with a stale `Upload-Offset`; expect **409** `Upload-Offset conflict`; then HEAD → resume at server offset → finish. Record offsets before/after each step.

**T3 — ⭐ Process-death resume (Android & iOS).**
1. Start uploading `big.bin` (≥100 MB) at a throttled rate (network conditioner / 3G) so the kill lands at 30–50 %.
2. Log the last confirmed `Upload-Offset` (from the last 204).
3. **Kill hard:** Android `adb shell am force-stop <pkg>`; iOS Xcode "Stop" (SIGKILL). Do not let the app flush anything beyond the persisted URL+offset record.
4. Relaunch; the app must read its persisted `{url, offset, expires?}` record and issue **HEAD first** — record the returned `Upload-Offset` (must be ≥ last confirmed, ≤ `Upload-Length`).
5. Resume PATCH from the *server-reported* offset to completion.
6. Verify integrity: Supabase — download the object via the authenticated object endpoint and compare `shasum -a 256` to the source; then SQL `select metadata->>'size', metadata->>'mimetype' from storage.objects where bucket_id='book-files' and name='…'` (row must exist only now — answers "materializes on completion"; `size` must equal byte count). Storyteller — check `/data/uploads/{bookUuid}/…` size + `GET /api/v2/books` shows the new book, then run T6-process.
7. Repeat once with the kill landing during a *single in-flight* PATCH (yank Wi-Fi instead of killing) — expect HEAD-recovery to shrink the gap to zero data loss beyond the unacknowledged chunk.

**T4 — Expiry probe (Supabase).** Parse `Upload-Expires` from T1/T2 responses; compute the window. If it's ~1 h, do a quick real wait cycle (upload 30 %, idle past expiry, HEAD → expect 410 → recreate). Record the exact expiry behaviour for the client state machine. (Storyteller: skip — no expiry expected; optionally HEAD a stale upload URL from yesterday's data to confirm persistence.)

**T5 — Cancel.** Start an upload, `DELETE` the URL mid-way → expect 204, then HEAD → 404/410 (Supabase: also try DELETE after completion → expect 400). Storyteller: confirm uploads dir cleanup expectations.

**T6 — Storyteller book flow end-to-end.** Generate `bookUuid` locally; upload 1 EPUB + 3 MP3s with `totalFiles=4` (flat filenames) on one URL per file; confirm the scan fires **once** after the 4th file and one book appears. Then `POST /api/v2/books/{uuid}/process` → expect 204 (and 409 in the negative case with only audio). Poll `/api/v2/jobs` for progress. Negative sub-test: upload with missing `bookUuid`, expect 405 only on the **final** PATCH (risk #4). Optionally verify `POST /api/v2/books/upload/finalize` recovery when one file is abandoned.

**T7 — Chunk-size semantics (Storyteller).** Set `STORYTELLER_MAX_UPLOAD_CHUNK_SIZE=10`, GET `/api/v2/settings/maxUploadChunkSize` (record raw JSON — resolves the MB/bytes question), then send a 12 MB PATCH. Expected: **204** (advisory only). Record the effective Uppy-style chunking the web client uses for comparison.

**T8 — Auth refresh mid-upload.** Supabase: let the user JWT expire (or swap in a stale token) → PATCH → expect 401; refresh via supabase-kt auth → PATCH again on the **same URL** → 204. Storyteller: same with a 30-day token (simulate by revoking/logging out) — record 401 shape. (HEAD on Supabase must still work while unauthenticated.)

### 5.3 Acceptance criteria

- [ ] Both endpoints create uploads and return stable `Location`s that decode/describe the target object unambiguously.
- [ ] Per-chunk `Upload-Offset` progress observed on both; UI progress monotonic.
- [ ] 409 on stale offset, recovery via HEAD proven on both (T2).
- [ ] **Process-death resume proven on Android AND iOS** (T3): resumed at server offset, zero manual intervention beyond relaunch, final SHA-256 == source on both backends.
- [ ] Supabase: object row appears **only after completion** with `metadata->>'size'` == file size (T3.6).
- [ ] Storyteller: book record created from client-generated `bookUuid` after last file; `process` returns 204 and jobs advance (T6).
- [ ] Expiry window measured on Supabase and encoded in the client state machine (recreate-on-410 path exercised) (T4).
- [ ] Cancel path works (T5); retry-with-backoff survives a mid-chunk network drop (T3.7).
- [ ] Chunk sizes: at least one size >6 MB proven viable end-to-end on each backend (T2/T7) — or documented as gateway-blocked with the working size.
- [ ] Token refresh mid-upload resumes the same URL (T8).
- [ ] Client-side hash hook demonstrated (hash computed during read; Supabase `user_metadata` round-trip optional).

### 5.4 What to record for the implementation

Per request, in a single CSV/HAR per device-run: timestamp (start/end), method, URL, `Tus-Resumable`, `Upload-Offset`/`Upload-Length` sent, `Content-Length` of body, auth header type present (Bearer/apikey/x-signature — values redacted), response status, response `Upload-Offset`, `Upload-Expires`, `Tus-Complete`, `Location`, error body, bytes acked, duration ms, throughput MB/s. Plus per test: source-file SHA-256, final object/file SHA-256, and the decoded Supabase upload id. These values seed the client constants (chunk size, backoff schedule, expiry margin) and the unit tests' canned responses.
