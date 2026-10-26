# Course World Template

This repository is the template for a term's GUPS course world.

## Starting a New Term

1. Click **Use this template** on GitHub to create the term's repository (e.g. `world-2027`).
2. Clone your repository locally:
   ```bash
   git clone git@github.com:<org>/<repo>.git
   cd <repo>
   ```
3. Check `MODULE.gups` to configure the engine version, checkpoint cadence, and default starting room.
4. Update `CODEOWNERS` to replace `@CHANGE_ME_INSTRUCTOR` with the instructor's GitHub handle (e.g. `@prof-smith`), and assign student teams to their zone packages:
   ```
   /MODULE.gups @prof-smith
   /rules/ @prof-smith
   /lib/ @prof-smith
   /skills/ @prof-smith
   /zones/tutorial/ @prof-smith

   # Student zones
   /zones/goblin_caves/ @student1 @student2
   /zones/forest/ @student3 @student4
   ```

## Importing the Roster

Create student accounts from your class roster CSV (`login,github_handle,role`):

```bash
gups import-roster roster.csv --world .
```

This outputs each student's login and initial 7-day setup code to distribute to students.
If the course server is already running with an admin port, import directly through the admin port:

```bash
gups import-roster roster.csv --admin 127.0.0.1:9090
```

## Running Locally

To test packages and play locally in developer mode:

```bash
gups serve --dev --world .
# Connect in another terminal:
telnet localhost 4000
```

Run zone tests:

```bash
gups test //...
```

Run static checks:

```bash
gups lint //...
```

## Production & Deployments

Run the production server with TLS telnet, HTTPS/WebSocket, and the admin port enabled:

```bash
gups serve --prod \
  --config /etc/gups/server.gups \
  --telnet-tls :4443 \
  --https :443 \
  --tls-cert /etc/gups/fullchain.pem \
  --tls-key /etc/gups/privkey.pem \
  --admin 127.0.0.1:9090
```

When changes merge to the `main` branch, CI or an instructor deploys the commit:

```bash
gups deploy --commit <SHA> --admin 127.0.0.1:9090
```

## Production Migration Snapshots

Every pull request runs static and dynamic CI gates, including the **Migration Check** (gate 5),
which verifies that prototype version bumps have `migrate()` definitions and reconciliation produces
no unexpected tombstones.

To provide the baseline snapshot for the migration check:

1. Generate a sanitized snapshot (personal data such as account passwords and tokens are stripped):
   - Against a running server:
     ```bash
     gups snapshot --sanitized --out snapshot.db --admin 127.0.0.1:9090
     ```
   - Offline directly from the database:
     ```bash
     gups snapshot --sanitized --out snapshot.db --world .
     ```
2. Create a GitHub release tagged `migration-snapshot` in this repository:
   ```bash
   gh release create migration-snapshot --title "Migration Snapshot" snapshot.db
   ```
3. To update the snapshot periodically during the term, recreate or upload `snapshot.db` to the `migration-snapshot` release tag:
   ```bash
   gh release upload migration-snapshot snapshot.db --clobber
   ```

If no snapshot has been uploaded yet, CI skips the migration check with a warning so early PRs pass cleanly.
