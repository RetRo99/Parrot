"""Run explicitly against a disposable, CLI-linked hosted project.

Creates/deletes its own confirmed Auth user and book. Calls the configured AI
provider once (may incur cost). Never prints keys, JWTs or excerpts. Summaries
are printed only with explicit --show-summary; --excerpt-file supplies test text.
Requires logged-in Supabase CLI; uses only Python's standard library.
Usage: python3 supabase/tests/hosted_recaps_smoke_test.py PROJECT_REF
"""
import concurrent.futures
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def run(project, excerpt=None, show_summary=False):
    cli = "/opt/homebrew/bin/supabase"
    result = subprocess.run([cli, "projects", "api-keys", "--project-ref", project],
                            check=True, capture_output=True, text=True)
    keys = json.loads(result.stdout)["keys"]
    anon = next(k["api_key"] for k in keys if k["id"] == "anon")
    admin = next(k["api_key"] for k in keys if k["id"] == "service_role")
    base = "https://" + project + ".supabase.co"
    user_id = None

    def http(path, body=None, token=None, key=anon, method="POST"):
        req = urllib.request.Request(base + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={"Content-Type": "application/json", "apikey": key,
                     "Authorization": "Bearer " + (token or key)})
        try:
            with urllib.request.urlopen(req, timeout=140) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            # Return status only: no raw Auth/provider errors in diagnostic logs.
            error.read()
            return error.code, {}

    def sql(query):
        result = subprocess.run([cli, "db", "query", "--linked", "--project-ref", project, query],
                                capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError("fixture_database_query_failed")
        return json.loads(result.stdout)["rows"]

    try:
        email = "recap-smoke-" + str(uuid.uuid4()) + "@example.invalid"
        password = secrets.token_urlsafe(32)
        status, user = http("/auth/v1/admin/users", {"email": email, "password": password,
                           "email_confirm": True}, admin, admin)
        assert status in (200, 201), "test_user_creation_failed"
        user_id = user["id"]
        status, login = http("/auth/v1/token?grant_type=password", {"email": email, "password": password})
        assert status == 200, "test_sign_in_failed"
        jwt = login["access_token"]
        book_id = str(uuid.uuid4())
        session = "hosted-smoke-" + str(uuid.uuid4())
        sql("insert into public.cloud_feature_allowlist(cloud_user_id,feature) values ('" + user_id + "','recap'); "
            "insert into public.cloud_books(id,cloud_user_id,title) values ('" + book_id + "','" + user_id + "','Synthetic recap smoke fixture');")
        endpoint = "/functions/v1/generate-recap"
        payload = {"consentVersion": 2, "sessionId": session, "cloudBookId": book_id,
                   "endedAt": int(time.time() * 1000), "position": {"href": "chapter.xhtml", "totalProgression": 0.5},
                   "language": "en", "excerpt": "Mara discovered a hidden letter in the old observatory. "
                   "The letter revealed that her brother had sailed to the northern island. "
                   "She packed a lantern and boarded the ferry to find him. "
                   "During the crossing she met a sailor who agreed to guide her to the island tower.",
                    "lastSentence": "The sailor agreed to guide her to the island tower."}
        if excerpt is not None:
            payload["excerpt"] = excerpt
            payload["lastSentence"] = excerpt.strip().splitlines()[-1]
        assert http(endpoint, payload, jwt)[0] == 403, "missing_consent_not_refused"
        assert http(endpoint, {"operation": "consent", "enabled": True}, jwt)[0] == 200, "consent_failed"
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            responses = list(pool.map(lambda _: http(endpoint, payload, jwt), range(4)))
        assert all(status in (200, 202) for status, _ in responses), "concurrent_submission_failed"
        assert all(body.get("sessionId") == session for _, body in responses), "submission_identity_changed"
        assert http(endpoint, {**payload, "excerpt": payload["excerpt"] + " A different event happened."}, jwt)[0] == 409, "conflict_not_refused"
        print("PASS: live Auth, consent, concurrent duplicate admission and conflicting input")
        for attempt in range(30):
            status, page = http(endpoint, {"operation": "fetch", "sessionId": session}, jwt)
            assert status == 200 and len(page.get("items", [])) == 1, "lost_response_lookup_failed"
            record = page["items"][0]
            if record["state"] not in ("queued", "running"):
                break
            time.sleep(5)
        assert record["state"] == "completed", "provider_job_did_not_complete: " + record["state"]
        assert 0 < len(record.get("summary", "")) <= 600, "invalid_summary"
        if show_summary:
            print("GENERATED RECAP:", record["summary"])
        rows = sql("select count(*)::integer as jobs, bool_and(charged and excerpt is null and last_sentence is null) as clean "
                   "from public.recap_jobs where user_id='" + user_id + "';")
        assert rows == [{"jobs": 1, "clean": True}], "duplicate_jobs_or_unscrubbed_text"
        rows = sql("select count from public.recap_usage where user_id='" + user_id + "';")
        assert rows == [{"count": 1}], "quota_not_charged_once"
        assert http(endpoint, {"operation": "delete", "sessionId": session}, jwt)[0] == 200, "delete_failed"
        _, page = http(endpoint, {"operation": "fetch", "sessionId": session}, jwt)
        assert page["items"][0]["state"] == "deleted" and page["items"][0]["summary"] is None, "delete_not_propagated"
        assert http(endpoint, {"operation": "consent", "enabled": False}, jwt)[0] == 200, "withdrawal_failed"
        print("PASS: automatic pg_net wakeup, provider completion, one quota charge, text scrub, deletion and withdrawal")
    finally:
        if user_id:
            status, _ = http("/auth/v1/admin/users/" + user_id, token=admin, key=admin, method="DELETE")
            if status not in (200, 204):
                raise RuntimeError("synthetic_test_account_cleanup_failed")
            print("Synthetic test account and its cloud data deleted")


if __name__ == "__main__":
    try:
        excerpt = None
        if "--excerpt-file" in sys.argv:
            with open(sys.argv[sys.argv.index("--excerpt-file") + 1], encoding="utf-8") as source:
                excerpt = source.read()
        run(sys.argv[1], excerpt, "--show-summary" in sys.argv)
    except Exception as error:
        # Assertions contain fixed diagnostic codes only.
        print("FAILED:", str(error) if isinstance(error, (AssertionError, RuntimeError)) else type(error).__name__)
        sys.exit(1)
