# Prepared chapters in Parrot Cloud: run of 2026-10-10

Branch `tts/prepared-cloud`, worktree `.claude/worktrees/tts-investigation`.
One line per check: what was run, what the server answered, what the screen showed.
No key, password, token, project reference or account email appears in this file.
The linked project's reference is written as `<REF>` wherever a command needs it.

## Part A: the server, on the linked development project

A0  `supabase/.temp` is still git-ignored (`.gitignore:38` matches
    `supabase/.temp/project-ref`); `git status --short` shows nothing from it and
    nothing from it was committed.
A0  CLI present: supabase 2.119.0 at /opt/homebrew/bin/supabase. `supabase projects
    list` succeeded, so the stored login is valid; no credential was looked for.
A1  LINKED, yes. `supabase link --project-ref "$(cat ../../supabase/.temp/project-ref)"`
    from the main checkout's reference. Answered `{"project_ref":"<REF>","message":""}`,
    exit 0. No login prompt and no database password prompt: the CLI reused the
    existing login and provisions a temporary login role per connection.
A2  `supabase migration list --linked` -> "Initialising login role... Connecting to
    remote database..." then the list. STOP CONDITION HIT. **Two** migrations are
    pending, not one:
      20261009000000  local only, remote empty  parrot_cloud_saved_words.sql
      20261010000000  local only, remote empty  parrot_cloud_prepared_audio.sql
    All 31 other local migrations are applied and no remote migration is missing from
    the repository, so the divergence is one-directional.
    20261009000000 is NOT this work's: it is the saved-words/offline-dictionary
    feature (commit 9bf4f22a, "feat: add bundled offline English dictionary and saved
    words") and its own header says "Deploy before clients that push words. Existing
    clients need the unknown-type reader fix before words are shared with them".
    `supabase db push --linked` applies every pending migration in order, so it would
    deploy that other feature to the shared development project as a side effect of
    this work. The brief forbids that ("stop if any migration other than this work's
    is pending"), so nothing was applied. NOT APPLIED.
A2  There is no CLI route that applies one pending migration and skips an earlier
    one; `db push` and `migration up` both apply the whole pending chain in timestamp
    order. Applying 20261010000000 alone would also leave the remote's migration
    history out of order.
A3  NOT RUN, and not runnable here. The two pre-apply queries from
    PREPARED_AUDIO_ROLLOUT.md step 2 need a SQL session. `psql` is not installed and
    the CLI has no "run this SQL" subcommand, so there is no way to run them with the
    CLI alone. No credential was extracted to work around this.
A4  BASELINE NOT RUN, and not runnable here. `supabase test db --linked supabase/tests`
    answered, exit 1:
      {"_tag":"Error","error":{"code":"DockerRunError","message":"docker: command not
      found (podman also not found) - install Docker Desktop or Podman and ensure it
      is on PATH"}}
    `--linked` only changes which database pg_prove is pointed at; pg_prove itself
    still runs in a container. docker, podman, colima, psql and pg_prove are all
    absent from this machine (/usr/bin/prove exists but pg_prove, the TAP harness for
    Postgres, does not). So the 38 new assertions, and the baseline for the 20
    existing files, cannot be executed on this machine at all.
A4  Checked before contemplating any run: all 21 files in `supabase/tests/` begin with
    `begin` and end with `rollback`, so the suite would not have written anything.
A5  NOT APPLIED. Blocked twice over, independently:
      (a) the stop condition at A2 - another feature's migration is pending;
      (b) even with (a) cleared, applying it here would mean applying SQL whose 38
          assertions have still never been executed anywhere, which is exactly what
          PREPARED_AUDIO_ROLLOUT.md step 1 calls "not optional".
A6  NOT RUN (depends on A5).
A7  No corrective migration written: nothing was applied, so nothing could fail.
A7  No existing test edited and no existing migration edited.
A8  PREPARED_AUDIO_ROLLOUT.md updated with what this run established: the migration is
    still unapplied on the development project, the CLI link needs no password, the
    suite needs a container runtime, and the saved-words migration is in the way.
A-  The linked development project is left exactly as found: one link operation and
    two read-only commands (`projects list`, `migration list`) were all that touched
    it. No migration applied, no row written, no reset, no script under
    scripts/supabase run. Book backups are therefore unaffected - see B1 for the
    positive check from the app.

## Part B: the phone, end to end

