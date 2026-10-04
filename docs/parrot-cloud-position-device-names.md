# Follow-up: originating device names for Parrot Cloud positions

This is a proposed follow-up, not implemented by the book-detail UI change.
Currently `ServerPosition.deviceName` is optional and no adapter populates it.
The UI therefore uses “Another device”. Parrot Cloud is a good first backend
because both the client payload and the Supabase RPCs are under our control.

## Implementation instructions

1. **Create a persistent installation identity and a display label.** Store a
   random installation UUID in local preferences; do not use a hardware ID,
   serial number, or advertising ID. Obtain a platform/model label where
   available, optionally allowing a user-provided label later. Treat the label
   as display-only metadata, never as authentication or a conflict identifier.

2. **Stamp the device when local reading creates the position mutation.**
   Keep that identity with the queued mutation across offline retries. Do not
   stamp it only when uploading: copying or resolving a remote position must
   not falsely attribute the original reading to the uploading device.

3. **Add optional Cloud-specific payload metadata**, for example:

   ```json
   {
     "library_book_id": "…",
     "position": { "…": "existing locator and progress fields" },
     "source_device": { "id": "installation-uuid", "name": "Pixel Tablet" }
   }
   ```

   Update `ParrotCloudReadingPositionPayload` in
   `lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudReaderRepository.kt`,
   plus the outbox conversion in `ParrotCloudSyncAdapter.kt` and the request,
   pull, and conflict mapping in `ParrotCloudProgressTransport.kt` in that folder.
   Default the new metadata to null so existing payloads still decode.
   **Simply assigning `ServerPosition.deviceName` is insufficient: it is
   `@Transient`, so it is not serialized.** Use an explicit Cloud DTO field;
   do not leak new fields into unrelated Storyteller/Audiobookshelf payloads.

4. **Preserve the metadata in Supabase with a new migration.** The current
   `push_sync_changes` RPC reconstructs `reading_payload` using only
   `library_book_id` and `position`, discarding other top-level keys. Update the
   RPC to validate/retain `source_device` in both `reading_positions.payload`
   and `sync_changes.payload`; conflict responses must return the original
   source. Use sensible length bounds and keep existing ownership checks,
   revisions, and idempotency unchanged. Existing JSONB payload storage can
   carry the metadata; a separate device registry is not required for this
   minimal approach. Do not modify already-applied migration files.

5. **Carry it through sync and local persistence.** Extend the relevant
   contracts in `feature/sync/domain/.../ProgressSyncTransport.kt`, mapping in
   `feature/sync/data/.../ProgressSyncEngine.kt`, and position database
   entities/storage in `lib/database`. Retain the label on remote candidate
   positions, including across app restarts. Map it back in
   `ParrotCloudReaderRepository.getRemotePosition()` to
   `ServerPosition.deviceName`. The existing UI mapping then carries it to
   `BookProgressInfoUiModel.remoteDeviceName` automatically.

6. **Keep attribution separate from conflict resolution.** Do not include
   device labels in locator/progress equality, conflict selection, revision
   comparisons, or echo detection. A rename must not manufacture a position
   conflict. Missing/blank labels continue to use “Another device”.

## Verification

- Device A saves progress; device B sees A's label, not B's.
- Ahead and behind presentations use the same originating label.
- Offline queue retries and app restarts retain the original attribution.
- A conflict response retains the remote source rather than replacing it with
  the local uploading device.
- Old/unnamed payloads still decode and show “Another device”.
- Existing progress conflict/echo/idempotency tests remain unchanged and pass.
- Supabase tests cover metadata persistence, validation, ownership, and pull.
