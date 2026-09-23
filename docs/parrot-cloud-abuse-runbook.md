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

## 4. Takedown procedure (mechanics — planned in Slice 5)

Identify → act → block → verify → record. All ops actions run with service-role
credentials via `scripts/supabase/ops/takedown.sh` or direct RPC:

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

2. **Takedown**: `admin_takedown_book_file(cloud_book_file_id, reason, actor)` —
   in one transaction: deletes the Storage object, sets
   `cloud_book_files.status = 'deleting'` (then removes/tombstones the row),
   emits the `book_file` change event clients pull on next sync, writes
   `cloud_file_audit_events` (`action='takedown'`, reason code, actor).
3. **Block re-upload**: `admin_block_content_hash(content_hash_algorithm,
   content_hash, reason, actor)` — enforced at all three gates
   (`reserve_book_upload`, `finalize_book_upload`, `create_book_download`).
   Optional hardening (recommended default): takedown auto-inserts the
   block-list row.
4. **Verify**:

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
- Eviction is crash-safe and retried on subsequent syncs until confirmed gone.

## 6. Reinstatement / counter-notice **[LEGAL REVIEW]**

Process (grounds, timelines, notice-forwarding) is legal's call. **Mechanics gap
to close in Slice 5**: we planned `admin_block_content_hash` but no inverse.
Add `admin_unblock_content_hash(algorithm, hash, reason, actor)` and a
`cloud_book_files` restore path (or accept clean re-upload after unblock —
recommended: clean re-upload; the local file on the user's device is intact per
§5). Record unblock actions in `cloud_file_audit_events` like takedowns.

## 7. Evidence & logging

`cloud_file_audit_events` (created in Slice 1) records, per event: actor cloud
user id, `cloud_book_id` / `cloud_book_file_id` / `upload_id`, content hash
(+algorithm), action (`reserve`, `finalize`, `download_grant`, `cancel`,
`takedown`, `block`, `unblock`), reason, timestamp.

**Never logged** (PC-plan §12): titles, file contents, tokens, signed URLs.
Client analytics for transfers follow the same rule.

Retention: **[LEGAL REVIEW]** — proposed: audit events retained 180 days minimum
(configurable), then a scheduled purge; `sync_mutations` idempotency rows follow
their own retention. Add the purge job to Slice 5 scope once retention is set.

## 8. Account-level action

- Repeat-infringer policy (warnings → suspension → termination): **[LEGAL
  REVIEW]** — product mechanics exist: PC-plan §11 account deletion removes all
  account data + storage per retention policy.
- Quota abuse: per-account quota is 5 GB (decision #6), enforced by
  `reserve_book_upload`; inspect usage with `get_storage_usage()`. There is
  intentionally **no per-file size cap** (decision #6) — if upload abuse shows
  up in practice, revisit.

## 9. Release-gate checklist (before enabling file upload)

- [ ] ToS text reviewed & approved **[LEGAL REVIEW]**
- [ ] Upload-rights attestation text reviewed & approved **[LEGAL REVIEW]** (see
      §10)
- [ ] Privacy policy covers user-uploaded files & retention **[LEGAL REVIEW]**
- [ ] This runbook approved; abuse channel live and staffed
- [ ] Audit retention configured (§7)
- [ ] Reinstatement mechanics shipped (§6 gap)
- [ ] Platform store-policy review for user-uploaded copyrighted content
      (Play / App Store) **[LEGAL REVIEW]**
- [ ] Server-controlled feature flag + `supportsBookUpload/BookDownload/
      BookDeletion` ready to flip (`lib/server/api/.../ServerCapabilities.kt`)

## 10. Legal text — working draft exists

Draft ToS clauses, attestation UI copy (all four touchpoints), takedown intake
and counter-notice text: **`docs/parrot-cloud-legal-text-draft.md`** — working
draft for counsel review, not approved for production. Every item below is
drafted there (section refs); counsel must resolve the draft's §F open items
(most importantly the DMCA vs EU DSM Art. 17 regime fit) before enablement:

1. User rights representation — drafted (§A2, §B1 checkbox).
2. Private/no-sharing acknowledgment — drafted (§A4, §B1 subtext).
3. Takedown + counter-notice acceptance — drafted (§A7, §A8, §C, §D).
4. Version pinning (`tos_version`, `attestation_version`) — mechanics unchanged
   (plan Slice 5); draft defines the version schemes (§E).
5. Touchpoints (registration, auto-backup enablement, manual Back up dialog,
   import-time prompt) — UI copy drafted per touchpoint (§B1–B4) with suggested
   `strings.xml` keys.
