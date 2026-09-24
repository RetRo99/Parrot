# Parrot Cloud abuse & takedown runbook — DRAFT

Status: **draft operational runbook** for the file-backup feature (see
`docs/parrot-cloud-book-file-transfer-implementation-plan.md`, Slice 5). Sections
marked **[LEGAL REVIEW]** are placeholders for counsel — this document is *not*
legal text and does not draft any. Per PC-plan §12, a documented abuse and
takedown process must exist **before** file upload is publicly enabled.

## 1. Scope & posture

Parrot Cloud stores user-provided book files in **private per-user storage**
(bucket `book-files`, path `users/{cloudUserId}/books/{cloudBookId}/{fileId}`).
Posture (product constraints):

- Private storage only — no public links, sharing, discovery, or indexing.
- No content fingerprinting, matching against external databases, or proactive
  scanning (explicit non-goals).
- Takedown is **reactive**, on specifically identified files, via content-hash
  block-list.
- Cross-account content dedupe deliberately does not exist.

This runbook covers: report intake → takedown → block-list → client invalidation
→ evidence retention → reinstatement.

## 2. Roles

| Role | Responsibility | Holder |
|---|---|---|
| Abuse contact | receives reports, acknowledges | **[LEGAL REVIEW]** channel/mailbox |
| On-call ops | executes takedowns (holds service-role access) | rotating |
| Legal reviewer | counter-notice, reinstatement, repeat-infringer policy | **[LEGAL REVIEW]** |

## 3. Report intake **[LEGAL REVIEW]**

Channel and published wording are legal's call. Mechanics: every report must
identify the content precisely enough to act. Required fields:

- Claimant name + contact + relationship to the rights holder.
- Identification of the material: **content hash** (sha-256-v1) or
  `cloud_book_file_id` / `cloud_book_id` if the claimant can supply it
  (**no titles are stored in our abuse tooling by design** — see §7).
- Description of the claimed work and the alleged infringement.
- Good-faith statement + accuracy statement — exact wording **[LEGAL REVIEW]**.

Response targets (acknowledge / act): **[LEGAL REVIEW]** — mechanics support
same-day action.

## 4. Takedown procedure (mechanics implemented; legal handling remains draft)

Identify → mark → remove object → finalize → verify. All ops actions run with
service-role credentials via `scripts/supabase/ops/takedown.sh`:

```sh
scripts/supabase/ops/takedown.sh \
  --file-id CLOUD_BOOK_FILE_UUID --reason CASE_REFERENCE --actor OPERATOR

scripts/supabase/ops/takedown.sh \
  --content-hash SHA256 --algorithm sha-256-v1 \
  --reason CASE_REFERENCE --actor OPERATOR
```

Set `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` in the operator's environment.
The script blocks a supplied content hash and processes every matching file in
`available` or `deleting` state; file-ID mode also adds the file's hash to the
block-list. It uses the Storage API for object removal, then calls the
completion RPC. The database never deletes `storage.objects` rows directly.

1. **Identify**: find the file row and its provenance.

   ```sql
   select f.id, f.cloud_book_id, f.cloud_user_id, f.storage_path,
          f.content_hash_algorithm, f.content_hash, f.status
   from public.cloud_book_files f
   where f.content_hash = :hash or f.id = :cloud_book_file_id;
   ```

   Cross-reference upload history + attestation:

   ```sql
   select upload_id, cloud_user_id, rights_attestation, created_at
   from public.cloud_book_uploads
   where content_hash = :hash;
   ```

2. **Mark and audit**: `admin_takedown_book_file(cloud_book_file_id, reason,
   actor)` adds the file hash to the block-list, changes the file state to
   `deleting`, emits the change event, and records the takedown.
3. **Remove and finalize**: the script deletes the Storage object through the
   Storage API, then calls `complete_book_file_deletion`. The completion RPC
   verifies the object is absent before removing the metadata row, releasing
   quota, and publishing the `removed` event. Retries are idempotent.
4. **Block re-upload**: `admin_block_content_hash(content_hash_algorithm,
   content_hash, reason, actor)` — enforced at all three gates
   (`reserve_book_upload`, `finalize_book_upload`, `create_book_download`).
   Hash mode in the script then takes down every currently available matching
   file. `admin_unblock_content_hash` records the inverse decision.
5. **Verify**:

   ```sql
   select action, reason, actor, created_at
   from public.cloud_file_audit_events
   where content_hash = :hash order by created_at desc;
   ```

   plus: Storage object gone; `create_book_download` now rejects
   (`file_not_available` / `content_blocked`); `pull_sync_changes` shows the
   `book_file` change event.

## 5. Client-side invalidation (what happens on user devices)

Per decision #9 (takedown scope):

- On next sync, devices pull the `book_file` (`deleting`/`removed`) event →
  `RemoteFileAvailability.Deleting` → `None`.
- **App-provisioned copies are deleted**: `imported_books` rows with
  `origin = 'cloud_download'` matching the `cloud_book_file_id` (bytes + rows,
  via the "Remove download" machinery) and any reader-cache entries.
- **User-imported originals are kept** (the user's own device files, outside
  service custody). Metadata and reading progress are kept in all cases.
- The applier performs eviction before completing the pulled change, so failed
  local cleanup leaves the sync checkpoint available for retry.

## 6. Reinstatement / counter-notice **[LEGAL REVIEW]**

Process (grounds, timelines, notice-forwarding) is legal's call. The inverse RPC
`admin_unblock_content_hash(algorithm, hash, reason, actor)` is implemented and
records the operator decision. Reinstatement uses a clean re-upload after
unblocking; local original files are preserved per §5.

## 7. Evidence & logging

`cloud_file_audit_events` records, per event: actor cloud
user id, `cloud_book_id` / `cloud_book_file_id` / `upload_id`, content hash
(+algorithm), action (`reserve`, `finalize`, `download_grant`, `cancel`,
  `expire`, `orphan_gc`, `delete_requested`, `delete`, `takedown`, `block`,
  `unblock`), reason,
timestamp. Administrative hash-only block/unblock events may have null book and
user identifiers; the algorithm, hash, reason, and actor remain recorded.

**Never logged** (PC-plan §12): titles, file contents, tokens, signed URLs.
Client analytics for transfers follow the same rule.

Account deletion keeps abuse-history events for up to 180 days with the account
UUID removed from `cloud_user_id`. The free-form `actor` field is redacted to
`deleted_account` only when it held the deleted account's own identifier;
operator identities on takedown/block events are preserved. The event
details and content hashes remain during that window, then are purged. The
server-side deletion function also purges expired events while processing a
request; configure `scripts/supabase/ops/purge-audit.sh` as an hourly scheduled
job so retention does not depend on account deletions. Use the deployment
scheduler's secret store to inject `SUPABASE_URL` and
`SUPABASE_SERVICE_ROLE_KEY`; do not put credentials in this repository or a
world-readable crontab. Local development uses
`scripts/supabase/ops/purge-audit-local.sh`, which takes its key from the local
Storage container and is pinned to loopback; an hourly user LaunchAgent is
installed for this workspace. This local job does not satisfy the production
scheduler release gate. `sync_mutations` idempotency rows follow their own
retention.

### Orphaned upload objects

Expired, cancelled, and permanently failed uploads are cleaned up by
`scripts/supabase/ops/gc-orphans.sh`. Configure it as a service-role scheduled
job (every 15 minutes is the intended cadence), with `SUPABASE_URL` and
`SUPABASE_SERVICE_ROLE_KEY` injected from the operator's secret store:

```cron
*/15 * * * * /path/to/StoryTellerKMP/scripts/supabase/ops/gc-orphans.sh
```

The GC RPC expires due sessions, releases their reservations, and counts any
completed orphan object in `used_bytes`. The worker re-validates each claim via
`confirm_orphan_book_file_gc` (which also extends the lease) immediately before
the Storage API delete — a stalled or duplicated worker can never delete an
object a user has re-reserved — and runs under a lockfile with curl timeouts.
The completion RPC then removes the file row and releases the counted usage. A
failed worker claim becomes retryable after 15 minutes. Lazy expiry during a new
reservation performs the same quota accounting immediately; physical object
removal still requires the scheduled Storage API worker because SQL cannot
remove the backing Storage object (documented deviation from the transfer
plan's "delete at expiry" wording). Counted orphan bytes stay charged across an
upload retry — the replacement reservation covers only its declared size — and
are released when the object is actually deleted or netted off at a successful
finalize.

## 8. Account-level action

- Repeat-infringer policy (warnings → suspension → termination): **[LEGAL
  REVIEW]**. Account deletion is handled by the authenticated
  `delete-cloud-account` Edge Function. It freezes account writes, removes all
  objects under `users/{cloudUserId}` through the Storage API, redacts retained
  audit events, purges expired events, then deletes the Auth user. Failures
  leave the account in a retryable deletion state; do not delete the Auth user
  manually before its Storage cleanup completes.
- Quota abuse: per-account quota is 5 GB (decision #6), enforced by
  `reserve_book_upload`; inspect usage with `get_storage_usage()`. There is
  intentionally **no per-file size cap** (decision #6) — if upload abuse shows
  up in practice, revisit.

## 9. Release-gate checklist (before enabling file upload)

- [ ] ToS text reviewed & approved **[LEGAL REVIEW]** — complete draft exists
      (legal text §A); awaiting sign-off only
- [ ] Upload-rights attestation text reviewed & approved **[LEGAL REVIEW]** (see
      §10) — complete draft exists (legal text §B)
- [ ] Privacy policy covers user-uploaded files & retention **[LEGAL REVIEW]**
      — complete draft exists (legal text §G)
- [ ] DMCA designated agent registered with the U.S. Copyright Office and
      listed in the main ToS imprint **[LEGAL/OPS]** (legal text §I)
- [ ] This runbook approved; abuse channel live and staffed
- [ ] Hourly audit-purge schedule configured with service-role credentials (§7)
- [ ] Orphan GC scheduled every 15 minutes with service-role credentials (§7)
- [x] Account deletion and retry behavior validated on the local Supabase stack
      (§8; Edge Function bundle and isolated-user failure/retry integration)
- [x] Reinstatement mechanics validated against the local Supabase stack (§6;
      `abuse_operations_test.sql`)
- [ ] Platform store-policy review for user-uploaded copyrighted content
      (Play / App Store) **[LEGAL REVIEW]**
- [x] Upload transport remains hard-disabled until approval; restore and
      deletion transports are separate
      (`lib/server-parrot-cloud/.../ParrotCloudBookFileTransferTransport.kt`)

## 10. Legal text — complete draft exists

ToS clauses, attestation UI copy (all four touchpoints), takedown intake and
counter-notice text, Privacy Policy addendum, and store disclosures:
**`docs/parrot-cloud-legal-text-draft.md`** — AI-drafted complete text, not yet
approved for production. All former §F open items are resolved there with
positions and rationale (including the regime fit: private locker → not a DSM
Art. 17 content-sharing service, with a DMCA-shaped notice flow; perjury
wording; retention schedule; repeat-infringer policy A11; restoration SLA A8).
Counsel must **verify those positions and sign off** before enablement; only
publish-time constants (legal text §I: operator entity, Abuse Contact, DMCA
agent registration) remain to be filled:

1. User rights representation — drafted (§A2, §B1 checkbox).
2. Private/no-sharing acknowledgment — drafted (§A4, §B1 subtext).
3. Takedown + counter-notice acceptance — drafted (§A7, §A8, §C, §D).
4. Version pinning (`tos_version`, `attestation_version`) — mechanics unchanged
   (plan Slice 5); draft defines the version schemes (§E).
5. Touchpoints (registration, auto-backup enablement, manual Back up dialog,
   import-time prompt) — UI copy drafted per touchpoint (§B1–B4) with suggested
   `strings.xml` keys.
