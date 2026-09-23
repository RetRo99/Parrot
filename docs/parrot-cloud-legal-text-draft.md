# Parrot Cloud backup — legal text working draft

Status: **WORKING DRAFT for counsel review — NOT legal advice and NOT
approved for production use.** Every section here must pass jurisdiction-
appropriate legal review before file backup is enabled (release gate:
`docs/parrot-cloud-abuse-runbook.md` §9, PC-plan §12). Bracketed `[PLACEHOLDERS]`
are for counsel/product to fill. Drafting posture: personal-locker service
(private per-user storage, no sharing, no discovery) — the safe-harbor framing
assumed throughout `docs/parrot-cloud-book-file-transfer-implementation-plan.md`.

Structure: (A) ToS clauses → (B) attestation UI copy (4 touchpoints) → (C)
takedown notice intake → (D) counter-notice → (E) versioning → (F) open items
for counsel.

---

## A. Terms of Service — "Parrot Cloud book backup" clauses

(Intended as an addendum/section of the main ToS. Suggested heading: **"Book
Backup Service"**.)

### A1. The Service

Parrot Cloud book backup ("the Service") stores copies of book files that you
provide and makes them available to your own devices. The Service is a personal
storage locker: it does not offer public sharing, publishing, discovery, or
distribution of any user's content.

### A2. Your content; your responsibility

Files you upload ("Your Content") remain yours. You are responsible for Your
Content. You represent and warrant that, for every file you upload, you own it
or have all rights and permissions needed to store it with us — including any
copyright in the text, audio, images, and other material it contains — and that
backing it up through the Service does not violate any law, license, or
agreement.

### A3. Limited license to us

You grant [ENTITY] a limited, non-exclusive, worldwide, royalty-free license to
host, store, transmit, reproduce, and make technical copies of Your Content
solely as necessary to operate the Service for you — including syncing it to
your other devices, verifying file integrity, and creating safety copies. We do
not use Your Content for anything else: no advertising, no sharing with other
users, and no use as training data for machine-learning models.

### A4. Private storage; no sharing

Your Content is stored privately and is accessible only through your account.
You may not use the Service to share, publish, distribute, or otherwise make
Your Content available to other people, or to circumvent technical or legal
restrictions of any rights holder. The Service provides no public links, no
discovery, and no search across users' content.

### A5. Acceptable use

You may not upload content that (a) infringes or misappropriates anyone's
intellectual property or other rights; (b) is unlawful in [JURISDICTION]; or
(c) violates these terms. We may refuse, remove, or block content and suspend
or terminate accounts in accordance with these terms and applicable law.

### A6. Storage limits

Each account has a storage quota (currently [5] GB). We may change quota and
technical limits with [30] days' notice. Files that exceed your quota cannot be
uploaded.

### A7. Copyright complaints and takedown

We respect intellectual property rights. If you believe content stored on the
Service infringes your copyright, send a notice to [ABUSE CONTACT] containing
everything listed in section C below. On receipt of a valid notice we will
remove or disable access to the identified material and may block re-uploading
of specifically identified files. We may, in appropriate circumstances and at
our discretion, disable or terminate the accounts of repeat infringers.

### A8. Counter-notice; restoration

If you believe your content was removed by mistake or misidentification, you
may send a counter-notice to [ABUSE CONTACT] containing everything listed in
section D below. If we receive a valid counter-notice, we may restore the
material unless the original claimant informs us that they have initiated a
legal proceeding, and we will follow the notice-and-counter-notice rules of
applicable law [COUNSEL: DMCA §512(f)/(g), EU DSM Art. 17, or other regime as
jurisdiction requires].

### A9. Removal, retention, and account deletion

You can delete individual backups or all of Your Content at any time in the
app. Deleted content is removed from active storage promptly and from safety
copies within [30] days. Deleting your account deletes Your Content and
associated data per our [Privacy Policy / retention policy REF]. We keep
limited operational records of uploads, downloads, and takedown actions (file
identifiers, content hashes, timestamps — not file contents, titles, or access
URLs) for abuse response and legal compliance, for [180] days [COUNSEL:
retention period].

### A10. Backup disclaimer

The Service is a convenience, not a guarantee. Keep your own copies of content
that matters to you. [See main ToS for warranty disclaimers and liability.]

---

## B. Attestation UI copy (the four touchpoints)

Plain-language, checkbox-gated. Suggested `strings.xml` keys (snake_case per
`translations/src/commonMain/composeResources/values/strings.xml`) included.
"the Terms" = A-clauses above.

### B1. Manual backup — `BookDetailScreen` backup confirmation dialog
(the checkbox gates Confirm; touchpoint 1 of the plan's attestation list)

- Dialog title: **Back up to Parrot Cloud?**
  (`cloud_backup_dialog_title`)
- Checkbox label: **I have the right to back up this file — I own it or have
  permission from the rights holder.** (`cloud_backup_attestation_checkbox`)
- Subtext under checkbox: Your file is stored **privately** and only for you.
  There is no sharing. We may remove it if a rights holder validly objects. See
  the [Book Backup Terms] (`cloud_backup_attestation_terms_link`)
- Confirm: **Back up** (`cloud_backup_confirm`) — disabled until checked.
- Dismiss: `general_cancel` (existing key)

### B2. Import-time prompt — `BooksListScreen` / `BooksListViewModel.importBook`
(fires when auto-backup is armed and no attestation exists; touchpoint 2)

- Title: **Back up imported books automatically?**
  (`cloud_backup_autobackup_prompt_title`)
- Body: New books you import will be uploaded to your private Parrot Cloud
  backup. You can change this anytime in Sync & Backup settings.
  (`cloud_backup_autobackup_prompt_body`)
- Checkbox label: same as B1 (`cloud_backup_attestation_checkbox` — shared)
- Confirm: **Turn on backup** (`cloud_backup_autobackup_enable`); Dismiss:
  **Not now** (`cloud_backup_autobackup_not_now`) (import itself proceeds
  either way — it is never blocked by this prompt)

### B3. Auto-backup enablement — `CloudAccountScreen` (Sync & Backup)
(touchpoint 4)

- Toggle label: **Back up new books automatically**
  (`cloud_backup_autobackup_toggle`)
- Confirmation dialog body (first enablement only): Books you import will be
  uploaded to your private Parrot Cloud storage, within your [5] GB quota. This
  counts against your quota and download bandwidth. By enabling, you confirm
  the checkbox below. (`cloud_backup_autobackup_confirm_body`)
- Checkbox label: same as B1 (`cloud_backup_attestation_checkbox` — shared)
- Bulk action label: **Back up all existing books**
  (`cloud_backup_backup_all`)

### B4. Registration — `CloudAccountScreen` registration form
(touchpoint 3)

- Checkbox label: **I agree to the [Terms of Service] (including the [Book
  Backup Terms]) and the [Privacy Policy].** (`cloud_account_tos_checkbox`)
- Submit disabled until checked. Record `{tos_version, attestation_version,
  attested_at}` at acceptance (section E).

---

## C. Takedown notice — intake text (runbook §3)

Published at [ABUSE CONTACT / web form]. Fields required (labels):

1. Your full name and contact details (email; postal address optional).
   (`abuse_form_claimant`)
2. Identification of the copyrighted work claimed to be infringed.
   (`abuse_form_work`)
3. Identification of the material to be removed: the **content hash (SHA-256)
   or file identifier** shown in the app's book details, plus the book title for
   reference only. (`abuse_form_material`)
4. Good-faith statement — fixed text, user affirms:
   **"I have a good faith belief that use of the material in the manner
   complained of is not authorized by the copyright owner, its agent, or the
   law."** (`abuse_form_goodfaith`)
5. Accuracy/authority statement — fixed text, user affirms:
   **"The information in this notice is accurate, and I am the copyright owner
   or authorized to act on the copyright owner's behalf. I understand that
   false statements may expose me to liability."**
   (`abuse_form_accuracy`) [COUNSEL: add "under penalty of perjury" where the
   applicable regime (e.g. DMCA §512(c)(3)) requires or permits it.]
6. Electronic signature (typed full name at minimum).
   (`abuse_form_signature`)

---

## D. Counter-notice — intake text (runbook §6)

1. Your full name and contact details. (`abuse_counter_claimant`)
2. Identification of the removed material and its former location (content
   hash / file identifier; book title for reference). (`abuse_counter_material`)
3. Statement under penalty of perjury [COUNSEL: confirm wording per regime]:
   **"I swear that I have a good faith belief that the material was removed as
   a result of mistake or misidentification."** (`abuse_counter_mistake`)
4. Consent to the jurisdiction of [COURTS] and acceptance of service of process
   from the original claimant (or their agent).
   (`abuse_counter_jurisdiction`)
5. Electronic signature. (`abuse_form_signature` — shared)

---

## E. Versioning (mechanics already planned)

- `tos_version`: e.g. `parrot-cloud-backup-tos-YYYY-MM-DD.N` — bumped on any
  A-clause change.
- `attestation_version`: e.g. `attest-rights-vN` — bumped whenever B1's
  checkbox substance changes.
- Every attestation record stores `{tos_version, attestation_version,
  attested_at}` and is attached to each `reserve_book_upload`
  (`cloud_book_uploads.rights_attestation`) — so any stored object can be tied
  to the exact words the user accepted. Re-attestation is demanded only when a
  version advances (plan Slice 5, touchpoint list item 5).

---

## F. Open items for counsel (do not skip)

1. **Regime fit**: DMCA §512 notice-and-counter-notice vs EU Digital Single
   Market Art. 17 (which requires more than notice-and-action for some uses)
   vs other local regimes — A7/A8/C/D wording is DMCA-flavored and must be
   localized.
2. **Perjury language** enforceability outside the US (see C5, D3).
3. **"Personal use" framing** — confirm the ownership/permission representation
   (A2) and the no-sharing rule (A4) give adequate cover in target markets.
4. **Retention** (A9: [30] days, [180] days) and GDPR/CCPA treatment of
   user-uploaded copyrighted files and abuse-response records.
5. **Repeat-infringer policy** (A7) — termination standard and appeal path.
6. **Store policies** (Play/App Store) for apps handling user-uploaded
   copyrighted content — disclosure obligations.
7. **Privacy Policy updates** covering backup content, content hashes in abuse
   records, and cross-device sync.
8. **Restoration SLA** for counter-notices (A8 currently deliberately vague:
   "may restore … in accordance with applicable law").
