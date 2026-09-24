# Parrot Cloud book backup — legal text

Status: **COMPLETE DRAFT — AI-drafted production text.** All previously open
drafting questions (former §F) are resolved here with concrete positions and
rationale. This is not legal advice: qualified legal review must verify the
resolved positions and sign off before publication (release gate:
`docs/parrot-cloud-abuse-runbook.md` §9, PC-plan §12). The only remaining
fill-ins are publish-time factual constants (§I) — there are no unresolved
drafting questions left.

Structure: (A) ToS clauses → (B) attestation UI copy (4 touchpoints) → (C)
takedown notice intake → (D) counter-notice → (E) versioning → (F) resolved
drafting decisions → (G) Privacy Policy addendum → (H) store disclosures →
(I) publish-time constants & prerequisites.

Drafting posture: **personal-locker service** — private per-user storage, no
sharing, no discovery, no public links. This posture is load-bearing: it is the
basis for the regulatory classification in §F1 and it matches the safe-harbor
framing of `docs/parrot-cloud-book-file-transfer-implementation-plan.md`.
Nothing in the product may contradict it (no sharing, no cross-user discovery,
no promotion of stored content).

---

## A. Terms of Service — "Parrot Cloud book backup" clauses

(An addendum/section of the main ToS. Heading: **"Book Backup Service"**.)

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
agreement. We rely on this representation; we do not and cannot verify the
provenance of your files.

### A3. Limited license to us

You grant [OPERATOR ENTITY — §I] a limited, non-exclusive, worldwide,
royalty-free license to host, store, transmit, reproduce, and make technical
copies of Your Content solely as necessary to operate the Service for you —
including syncing it to your other devices, verifying file integrity, and
creating safety copies. We do not use Your Content for anything else: no
advertising, no sharing with other users, and no use as training data for
machine-learning models. The license ends when Your Content is deleted, except
for safety copies that expire on the schedule in section A9 and records we must
keep for abuse response and legal compliance.

### A4. Private storage; no sharing

Your Content is stored privately and is accessible only through your account.
You may not use the Service to share, publish, distribute, or otherwise make
Your Content available to other people, or to circumvent technical or legal
restrictions of any rights holder. The Service provides no public links, no
discovery, and no search across users' content.

### A5. Acceptable use

You may not upload content that (a) infringes or misappropriates anyone's
intellectual property or other rights; (b) is unlawful under applicable law; or
(c) violates these terms. You may not use the Service to circumvent digital
rights management or other technical protection measures, or to store content
whose storage required such circumvention. We may refuse, remove, or block
content and suspend or terminate accounts in accordance with these terms and
applicable law.

### A6. Storage limits

Each account has a storage quota (currently 5 GB). We may change quota and
technical limits with 30 days' notice. Files that exceed your quota cannot be
uploaded.

### A7. Copyright complaints and takedown

We respect intellectual property rights. If you believe content stored on the
Service infringes your copyright, send a notice to the Abuse Contact (section I)
containing everything listed in section C below. On receipt of a valid notice we
will remove or disable access to the identified material and may block
re-uploading of specifically identified files. We may, in appropriate
circumstances and at our discretion, disable or terminate the accounts of repeat
infringers under the policy in section A11.

### A8. Counter-notice; restoration

If you believe your content was removed by mistake or misidentification, you may
send a counter-notice to the Abuse Contact (section I) containing everything
listed in section D below. When we receive a valid counter-notice we will:

1. promptly forward it to the original claimant (within 2 business days); and
2. restore the material within 10 to 14 business days after forwarding it,
   unless the claimant informs us that they have initiated a court proceeding
   seeking an injunction against the material.

Restoration returns the file to your account and unblocks re-uploading of that
file. These steps implement the notice-and-counter-notice mechanics of the U.S.
Digital Millennium Copyright Act (17 U.S.C. §512(g)) and are applied in
equivalent form in other markets (see §F1). Nothing in this section limits
either party's rights under applicable law.

### A9. Removal, retention, and account deletion

You can delete individual backups or all of Your Content at any time in the app.
Deleted content is removed from active storage within 7 days and from safety
copies within 30 days. Deleting your account deletes Your Content and associated
data as described in the Privacy Policy addendum (section G). We keep limited
operational records of uploads, downloads, and takedown actions (file
identifiers, content hashes, timestamps — not file contents, titles, or access
URLs) for abuse response and legal compliance, for 180 days; if you delete your
account, the account identifier is removed from those records immediately and
the remainder is purged at the end of that window. Files whose content hash has
been blocked under section A7 remain on a block-list for as long as needed to
prevent re-uploading.

### A10. Backup disclaimer

The Service is a convenience, not a guarantee. Keep your own copies of content
that matters to you. Warranty disclaimers and limitations of liability of the
main ToS apply to the Service.

### A11. Repeat-infringer policy

We terminate the accounts of repeat infringers in appropriate circumstances, as
required for safe-harbor protection (17 U.S.C. §512(i)). Our graduated policy:

1. **What counts.** A "strike" is recorded when we complete a takedown under
   section A7 for content you uploaded. Strikes are recorded per account.
2. **What does not count.** Counter-notices (section A8), retracted or
   withdrawn notices, and notices we reject as invalid do not count. If removed
   material is restored after a counter-notice, the related strike is removed.
3. **Graduated response** (rolling 12-month window):
   - strike 1: written warning;
   - strike 2: final written warning;
   - strike 3: suspension of backup privileges for 30 days (restores and
     deletions remain available so you can recover and remove content);
   - strike 4, or any case of willful or commercial-scale infringement at any
     time: termination of the account and deletion of Your Content.
4. **Appeal.** You may appeal any strike or termination to the Abuse Contact
   within 30 days. A person not involved in the original decision reviews the
   appeal and responds within 14 days. Successful appeals remove the strike.
5. **Reinstatement after a block.** If a content hash was blocked and the
   underlying dispute is resolved (counter-notice upheld, claim withdrawn, or
   court decision), the block is lifted and you may re-upload your own copy.

### A12. Changes to these terms

We may update these clauses; the version identifier (section E) changes on any
substantive amendment and the current version is shown in the app. Amendments
that affect Your Content or the backup service take effect no sooner than 30
days after notice. If an amendment changes the substance of the rights
attestation (section B1), you will be asked to re-attest before your next
upload.

---

## B. Attestation UI copy (the four touchpoints)

Plain-language, checkbox-gated. `strings.xml` keys (snake_case per
`translations/src/commonMain/composeResources/values/strings.xml`) included.
"the Terms" = the A-clauses above.

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
  uploaded to your private Parrot Cloud storage, within your 5 GB quota. This
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

Published at the Abuse Contact / web form (section I). A single form serves all
markets: its fields satisfy 17 U.S.C. §512(c)(3)(A)(i)–(vi) and the customary
notice-and-action requirements of EU Member States (§F1). Fields required
(labels):

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
   **"The information in this notice is accurate; and to the fullest extent
   permitted by law in my jurisdiction, under penalty of perjury, I am the
   copyright owner or authorized to act on the copyright owner's behalf. I
   understand that false statements may expose me to liability."**
   (`abuse_form_accuracy`)
6. Electronic signature (typed full name at minimum).
   (`abuse_form_signature`)

Notices missing any required element may be rejected with a request to cure;
rejection reasons are recorded (runbook §7).

---

## D. Counter-notice — intake text (runbook §6)

1. Your full name and contact details. (`abuse_counter_claimant`)
2. Identification of the removed material and its former location (content
   hash / file identifier; book title for reference). (`abuse_counter_material`)
3. Statement — fixed text, user affirms:
   **"To the fullest extent permitted by law in my jurisdiction, under penalty
   of perjury, I swear that I have a good faith belief that the material was
   removed as a result of mistake or misidentification."**
   (`abuse_counter_mistake`)
4. Consent to jurisdiction and service of process — fixed text, user affirms:
   **"I consent to the jurisdiction of the Federal District Court for the
   judicial district in which my address is located, or if my address is outside
   the United States, for any judicial district in which the service provider
   may be found, and I accept service of process from the person who filed the
   original notice or their agent. If I am a consumer resident in the European
   Economic Area or the United Kingdom, I may also bring proceedings in the
   courts of my country of residence."** (`abuse_counter_jurisdiction`)
5. Electronic signature. (`abuse_form_signature` — shared)

---

## E. Versioning (mechanics already planned)

- `tos_version`: `parrot-cloud-backup-tos-YYYY-MM-DD.N` — bumped on any
  A-clause change. The first published version is stamped at sign-off (§I).
- `attestation_version`: `attest-rights-vN` — bumped whenever B1's checkbox
  substance changes. The first published version is `attest-rights-v1`.
- Every attestation record stores `{tos_version, attestation_version,
  attested_at}` and is attached to each `reserve_book_upload`
  (`cloud_book_uploads.rights_attestation`) — so any stored object can be tied
  to the exact words the user accepted. Re-attestation is demanded only when a
  version advances (plan Slice 5, touchpoint list item 5).

---

## F. Resolved drafting decisions (formerly open items)

Each former open item is resolved below. A reviewer's job is to verify these
positions, not to fill blanks. Residual risk is stated honestly per item.

### F1. Regime fit — DMCA vs EU DSM Art. 17 vs others

**Decision:** Draft as a private locker that is **not** an "online
content-sharing service" under Art. 17(1) of the EU DSM Directive
(2019/790). Such services give the *public* access to content uploaded by
users; this Service stores content privately for the uploader alone, with no
sharing, discovery, or promotion — the CJEU's classification factors
(*YouTube/Cyando*, C-682/18: public access, specific knowledge, profit from
infringing content) do not point to OCSSP status. Consequently no Art. 17
content-sharing licensing regime applies. In the US, we rely on DMCA
safe-harbor conditions (§512(c) notice handling, §512(i) repeat-infringer
policy and standard technical measures, registered agent — §I).

Practically: one notice-and-action flow (sections C/D) built to satisfy
§512(c)(3)(A)(i)–(vi) *and* customary EU notice-and-action minimums (Art.
17(7)-style "sufficiently substantiated" notices), applied in every market. In
markets with unqualified notice-and-action or hosting duties beyond this
baseline (e.g., national implementations adding specific fields or deadlines),
the flow is extended locally rather than forked.

**Residual risk:** a regulator or court could classify the Service as an OCSSP
if the product ever grows public-facing features (sharing, discovery,
promotion). Mitigations: (a) the product must preserve the private-locker
posture (load-bearing — see header); (b) the reactive enforcement stack
(attestation, hash block-list, takedown, repeat-infringer policy) already
covers the "best efforts" obligations of Art. 17(2)–(3) to the extent they
could apply.

### F2. Perjury language outside the US

**Decision:** Use "under penalty of perjury" exactly where the US statute
attaches it — the accuracy/authority statement of the notice (C5,
§512(c)(3)(A)(vi)) and the mistake-or-misidentification statement of the
counter-notice (D3, §512(g)(3)(C)) — each qualified with "to the fullest
extent permitted by law in my jurisdiction". The good-faith statement (C4)
carries no perjury wording (the statute does not require it). This keeps full
US enforceability while avoiding overreach where perjury oaths are
unenforceable or inappropriate (civil-law jurisdictions).

**Residual risk:** in non-US jurisdictions the phrase is decorative rather
than enforceable; false-notice exposure there rests on general tort/abuse-of-
right doctrines and on A12/A5 remedies. Acceptable.

### F3. "Personal use" framing (A2 + A4 cover)

**Decision:** Rest exclusively on the user's ownership/permission
representation (A2) and the no-sharing/no-circumvention rules (A4, A5). We do
**not** rely on the private-copy exception of any jurisdiction — it is
inconsistent across markets and unavailable for DRM-circumventing copies. The
explicit anti-circumvention sentence in A5 covers DMCA §1201 / InfoSoc Art. 6
exposure (e.g., backing up DRM-locked ebooks via stripped files).

**Residual risk:** the representation is only as good as the user's honesty —
which is precisely why the enforcement layers (attestation capture per object,
takedown, hash block-list, A11) exist. This is the standard market posture for
personal-locker services.

### F4. Retention periods and GDPR/CCPA treatment

**Decision:** Concrete schedule (now reflected in A9 and §G):
deleted content — active storage ≤ 7 days, safety copies ≤ 30 days; abuse
records (`cloud_file_audit_events`) — 180 days, account identifier stripped on
account deletion (matches runbook §7 and `purge_expired_cloud_file_audit_events`);
block-list hashes — retained while blocked, because their purpose
(re-upload prevention) requires persistence. GDPR bases: contract (Art. 6(1)(b))
for service operation; legitimate interests (Art. 6(1)(f)) for security, quota
and abuse prevention; legal obligation (Art. 6(1)(c)) where notice-and-action
law applies. CCPA/CPRA: no "sale" or "sharing" of personal information;
service-provider role for hosted content.

**Residual risk:** block-list hash persistence is the only contestable period
(a hash of a user-supplied file can be personal data). Justification:
necessity for repeat-infringement prevention, low intrusiveness (one-way hash,
no content), no cross-referencing to other datasets. If a DPAs object, fall
back to time-boxing entries to 3 years with renewal on re-attempt.

### F5. Repeat-infringer policy — standard and appeal path

**Decision:** Full policy moved into A11 (it is a §512(i) condition, not fine
print): strikes per completed takedown, rolling 12-month window, graduated
warning → final warning → 30-day backup suspension → termination, willful/
commercial-scale infringement terminating at any time; counter-notices,
retractions and rejected notices never count; appeal to a non-involved reviewer
within 30 days, answered within 14 days.

**Residual risk:** "appropriate circumstances" is fact-dependent even with a
policy — the runbook's documented process (§3–§8) is the evidence of
reasonable implementation.

### F6. Store policies (Play / App Store) for user-uploaded copyrighted content

**Decision:** Compliance mapping and ready-to-paste disclosure text live in §H.
Both stores' UGC requirements (reporting, blocking, moderation, ToS enforcement)
are satisfied by: the abuse intake form (reporting), the hash block-list and
account suspension (blocking/moderation), A11 (enforcement), and the attestation
gates (pre-upload screening obligation posture). No sharing feature means no
"featured/curated UGC" surface to moderate.

**Residual risk:** store reviewers may still request an in-app reporting entry
point; §H3 specifies a minimal in-app link to the abuse form to satisfy this.

### F7. Privacy Policy updates

**Decision:** The Privacy Policy addendum is drafted in §G (backup content,
content hashes in abuse records, cross-device sync, retention schedule,
processors, no ML training, no sale/sharing).

**Residual risk:** none beyond counsel confirming consistency with the existing
main Privacy Policy; the addendum is written to slot in as a section.

### F8. Restoration SLA for counter-notices

**Decision:** Made concrete in A8: forward to claimant within 2 business days;
restore within 10–14 business days unless the claimant initiates proceedings —
tracking the DMCA §512(g)(2)(C) window so the SLA and safe-harbor mechanics
cannot drift apart.

**Residual risk:** none material; the window is statutory-shaped in the US and
contractual-protective elsewhere.

---

## G. Privacy Policy addendum — "Book Backup Service"

### G1. What the Service adds to our processing

When you use Book Backup, we process the book files you choose to upload, on
top of the account and sync data covered by the main Privacy Policy.

### G2. Data we process

| Data | Notes |
|---|---|
| Book files you upload | Content bytes; stored privately per account |
| File metadata | File name, size, media type, per-file technical identifier |
| Content hashes (SHA-256) | Integrity verification, deduplication within your account, abuse response, block-list enforcement |
| Transfer and audit records | Upload/download timestamps, status transitions, takedown and block actions — **never** file contents, book titles, access URLs, or access tokens |
| Your attestations | `{tos_version, attestation_version, attested_at}` per accepted backup confirmation |

### G3. Purposes and legal bases

| Purpose | Basis (GDPR) |
|---|---|
| Operating backup, sync, and restore | Contract (Art. 6(1)(b)) |
| Quota enforcement, security, fraud and abuse prevention | Legitimate interests (Art. 6(1)(f)) |
| Takedown/counter-notice handling and legal compliance | Legal obligation (Art. 6(1)(c)) / legitimate interests |

### G4. Retention

Per section A9: deleted content — active storage ≤ 7 days, safety copies ≤ 30
days; audit records — 180 days (account identifier stripped on account
deletion); block-list hashes — while the block is in force.

### G5. Processors and hosting

Backup content and records are stored with our cloud infrastructure provider
(Supabase, acting as processor under a data-processing agreement) and are
accessible only to authorized personnel for abuse response and operations, on
a need-to-know basis.

### G6. What we never do with Your Content

No advertising use, no sale or "sharing" of personal information, no
disclosure to other users, no cross-user matching or discovery, and no use as
training data for machine-learning models.

### G7. Your rights

Access, rectification, erasure, restriction, portability, and objection as
provided by applicable law. Most controls are in the app (delete a backup,
delete all content, delete account). Contact for all requests and complaints:
the Abuse Contact (section I), or your supervisory authority.

### G8. International transfers

Where content or records are processed outside your region, transfers are
covered by adequacy decisions or standard contractual clauses as described in
the main Privacy Policy.

---

## H. Store disclosures (Play / App Store)

### H1. Listing / privacy disclosure text (ready to paste)

> **User-uploaded content:** Parrot Cloud book backup lets you store private
> copies of book files you own or have permission to back up. Files are visible
> only to you; there are no public links, sharing, or discovery features. You
> must have the right to back up every file you upload. We respond to copyright
> notices, block identified files from being re-uploaded, and terminate repeat
> infringers. See "Book Backup Terms" in the app. Reports:
> [ABUSE CONTACT URL].

### H2. Requirement mapping

| Store requirement | Our mechanism |
|---|---|
| UGC terms / ToS acceptance | Sections A + B4 registration checkbox |
| Reporting mechanism | Abuse form (section C) — also linked in-app (H3) |
| Blocking & moderation | Hash block-list + `admin_takedown_book_file` (runbook §4–§5) + account suspension (A11) |
| Repeat-offender enforcement | A11 (graduated, appeals) |
| Privacy disclosures | §G addendum; store Data Safety / privacy labels updated to include "Photos/Videos/Files" style personal files category |
| Timely response to violations | Runbook §3 SLA: acknowledge ≤ 2 business days, action on valid notices ≤ 5 business days |

### H3. In-app reporting entry point

Book detail → overflow menu → **Report a copyright issue** (`cloud_backup_report_issue`),
opening the abuse form URL (section I) with the file's content hash
pre-filled as selectable text. Satisfies in-app reporting expectations without
introducing any cross-user surface.

---

## I. Publish-time constants & prerequisites

Fill and freeze at sign-off; no drafting left:

| Constant | Value | Where used |
|---|---|---|
| `[OPERATOR ENTITY]` legal name + address | _TBD at publication_ | A3 |
| Abuse Contact (email + form URL) | _TBD at publication_ | A7, A8, C, D, A11, H1, H3 |
| Courts (non-EU residual) | operator's home jurisdiction | D4 |
| Effective date + first `tos_version` | `parrot-cloud-backup-tos-YYYY-MM-DD.1` | E |
| First `attestation_version` | `attest-rights-v1` | E |

Prerequisites before publication (verify in runbook §9):

1. **DMCA designated agent registered** with the U.S. Copyright Office
   (§512(c)(2) safe-harbor precondition) and listed in the main ToS imprint —
   registration details are a publish-time artifact, the obligation is fixed.
2. Counsel sign-off on this document (verification of §F positions per market
   of launch).
3. Main ToS / Privacy Policy cross-references updated to include this addendum.
4. Store listing text (H1) live and Data Safety / privacy labels updated (H2).
