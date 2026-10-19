# Deploying the FIX gateway

```
git push  ──►  GitHub Actions: all tests + build  ──►  GitHub Release (fix-gateway.jar)
                                                              │
you, on the gateway server:   /opt/aurify-fixgateway/update.sh  ◄┘
```

GitHub only builds. **Nothing connects to the server** - you install a build
yourself with one command.

## What happens

**On every push to `wip-ashik`** (`.github/workflows/build-gateway.yml`):

1. `mvn verify` - every test must pass.
2. `deploy/test-deploy.sh` - rehearses the install scripts (swap, rollback,
   pruning) on Linux.
3. The jar is published as a release named `gateway-<build>-<commit>` on the
   repo's **Releases** page, with its SHA-256. The newest 15 are kept.

If any step fails, no release is published.

**When you run `update.sh` on the server:**

1. Downloads the newest release and checks its SHA-256.
2. Points `fix-gateway.jar` at it and restarts `aurify-fixgateway`.
3. Waits up to 120 s for `/actuator/health` = UP and gRPC port 9090.
4. Not healthy? Switches back to the previous jar by itself and tells you.

Only the jar changes - `certs/`, `data/` (FIX sequence numbers), `logs/` and
the systemd unit are never touched. The last 5 jars stay on the server.

Expect a few seconds without prices on each install: the FIX sessions log out
and back in. Node retries the gateway for 20 s, so hedge orders placed during
the restart wait instead of failing.

## One-time setup on the server

After the first build has been published, run on the gateway instance (as
`ubuntu`, not root):

```bash
curl -fsSL -o /opt/aurify-fixgateway/update.sh https://github.com/AurifyAE/aurify-fixclient/releases/latest/download/update.sh && chmod +x /opt/aurify-fixgateway/update.sh
```

`update.sh` keeps itself up to date after that.

## Day-to-day

| You want to | Run on the gateway server |
|---|---|
| Install the newest build | `/opt/aurify-fixgateway/update.sh` |
| Install a specific build | `/opt/aurify-fixgateway/update.sh gateway-42-abc123def456` |
| Go back to the previous jar | `/opt/aurify-fixgateway/update.sh --rollback` |
| See what's running + history | `/opt/aurify-fixgateway/update.sh --status` |
| Install a jar you copied over | `/opt/aurify-fixgateway/update.sh ~/fix-gateway.jar` |

Run them as `ubuntu`, not with `sudo` - the scripts call `sudo` only for
`systemctl` and `journalctl`.

To build from another branch, change `branches:` in
`.github/workflows/build-gateway.yml`.
