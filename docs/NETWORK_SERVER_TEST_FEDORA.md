# Beta 6.1: Fedora Navidrome test server over private HTTPS

Moka's experimental Subsonic client requires an HTTPS base URL. Fedora/Podman runs
Navidrome locally; Tailscale Serve adds an HTTPS endpoint **inside your tailnet**.
No router port forwarding or public Funnel is needed.

## 1. Prerequisites and storage

```bash
df -h "$HOME" /var
command -v podman || sudo dnf install -y podman
command -v tailscale || echo "Install and sign in to Tailscale before Step 4"
```

**Important:** Rootless Podman stores images under your home directory by default. If
`/home` is almost full, free space or change the storage setup *before pulling* the
image. Do not blindly download container images onto a full filesystem.

Choose an existing music folder with at least one real audio file. If your only
music is on a phone, copy a few test songs onto Fedora first. Use a dedicated
test folder if you don't want container SELinux labeling to touch the existing
music directory:

```bash
export MUSIC_DIR="$HOME/Music"   # change to your actual existing music directory
test -d "$MUSIC_DIR" || { echo "Music folder not found: $MUSIC_DIR"; exit 1; }
export NAVIDROME_DATA="$HOME/.local/share/moka-navidrome"
mkdir -p "$NAVIDROME_DATA"
```

## 2. Start Navidrome

```bash
podman run -d --replace \
  --name moka-navidrome \
  --userns=keep-id \
  -p 127.0.0.1:4533:4533 \
  -v "$NAVIDROME_DATA:/data:Z" \
  -v "$MUSIC_DIR:/music:ro,z" \
  -e ND_LOGLEVEL=info \
  docker.io/deluan/navidrome:latest
podman ps --filter name=moka-navidrome
podman logs --tail 35 moka-navidrome
```

On SELinux-enabled Fedora, `:z` relabels the chosen music folder for shared
container access. If that folder is used by other confined services, consider
copying two songs into a dedicated test folder instead. The music volume is
read-only to Navidrome; its database in `/data` is writable.

### Low-space Fedora alternative (recommended if `/home` is full)

Rootless Podman usually stores downloaded images under `/home`. If that filesystem
does not have enough free space, use a dedicated test-music directory under
`/var/tmp` and rootful Podman storage under `/var` **instead of Step 2**:

```bash
export MUSIC_DIR="/var/tmp/moka-test-music-$USER"
mkdir -p "$MUSIC_DIR"
# Copy at least one real .mp3, .flac or .wav file into "$MUSIC_DIR" before continuing.
sudo mkdir -p /var/lib/moka-navidrome
sudo chown "$(id -u):$(id -g)" /var/lib/moka-navidrome
sudo podman run -d --replace \
  --name moka-navidrome \
  --user "$(id -u):$(id -g)" \
  -p 127.0.0.1:4533:4533 \
  -v /var/lib/moka-navidrome:/data:Z \
  -v "$MUSIC_DIR:/music:ro,Z" \
  -e ND_LOGLEVEL=info \
  docker.io/deluan/navidrome:latest
sudo podman ps --filter name=moka-navidrome
sudo podman logs --tail 35 moka-navidrome
```

This keeps images/database off the almost-full `/home`, and avoids SELinux
relabeling of your entire personal music library. For logs or stopping the server
under this alternative, use `sudo podman` rather than `podman`.

## 3. Create an administrator account

In your Fedora browser, open `http://127.0.0.1:4533`. The first-run page
should prompt to create the Navidrome administrator username/password.
Allow time for it to scan the test music folder and check that albums appear.
You can use a separate, non-admin Navidrome user for Moka.

## 4. Provide HTTPS using Tailscale Serve

First ensure Fedora is logged into Tailscale. If not, complete `sudo tailscale up`
and any login instructions. Check with `tailscale status`. Your other device
must also join the same tailnet to use the private HTTPS endpoint.

```bash
tailscale serve --bg 4533
tailscale serve status
```

Tailscale may ask you to enable HTTPS certificates for the tailnet. It should
show an address similar to `https://fedora.example-tailnet.ts.net` (your
actual hostname will differ). Use the **exact HTTPS URL printed by Serve**.

Do **not** run `tailscale funnel`, which would expose the service publicly.

## 5. Connect and test

In Moka Beta 6.1 → Settings → Network music · experimental:

- Server HTTPS URL: the complete `https://...ts.net` address (no `/rest` path).
- Username/password: Navidrome user created in Step 3.
- Tap Connect → browse albums → select a track for playback.

Until the APK can launch on a test device, a Fedora Python Subsonic smoke test
can verify authentication, album/song endpoints and partial audio streaming
against this address. It cannot validate Moka's Android UI or DSP.

The current app client expects valid trusted TLS certificates, so plain
`http://127.0.0.1:4533` is **not** a valid Moka server URL.

## Troubleshooting

```bash
curl -I http://127.0.0.1:4533
podman logs --tail 70 moka-navidrome
tailscale status
tailscale serve status
```

- Empty library: check files actually exist under `$MUSIC_DIR`, inspect logs
  and check `--userns=keep-id` can read the music folder.
- TLS/hostname failure: check tailnet HTTPS certificate setup and use the
  `tailscale serve status` URL, not the local HTTP URL.
- Connection refused: check the container and Serve are both running.
- Credential failure: sign in to the local Navidrome web interface with the
  same account.
- Stop private proxy: `tailscale serve --https=443 off` (only if using default
  port 443). Stop server: `podman stop moka-navidrome`.

Reference: https://navidrome.org/docs/installation/podman/
Reference: https://tailscale.com/docs/reference/tailscale-cli/serve
