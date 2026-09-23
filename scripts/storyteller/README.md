# Local Storyteller server

This Compose setup follows Storyteller's [self-hosting guide](https://storyteller-platform.dev/docs/installation/self-hosting).
It binds the web/API port to localhost, stores server data in
`~/Documents/Storyteller`, and keeps the generated authentication secret in
`~/Library/Application Support/Storyteller` with owner-only permissions.

The official Storyteller image needs roughly 10 GiB of free storage and 8 GiB
of memory. Start it with:

```bash
scripts/storyteller/up.sh
```

Open <http://localhost:8001> to create the initial administrator account. The
server API and TUS upload endpoint are then available on the same local origin.
Do not change the secret file after creating the account; it signs Storyteller
authentication tokens.

To stop the container while retaining its data:

```bash
docker compose \
  --project-name storyteller-local \
  --file scripts/storyteller/compose.yaml \
  down
```
