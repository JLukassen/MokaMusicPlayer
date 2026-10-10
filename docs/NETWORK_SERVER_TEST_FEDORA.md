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

## 5. Access Navidrome from Android over Tailscale

The Fedora host (not the container) runs Tailscale. Keep the Podman Navidrome
port bound to 127.0.0.1:4533. You do NOT need Tailscale inside Navidrome.

~~~bash
sudo systemctl enable --now tailscaled
tailscale status
curl -I http://127.0.0.1:4533
tailscale serve --bg 4533
tailscale serve status
~~~

If prompted, enable MagicDNS and HTTPS certificates in your Tailscale
admin DNS settings. Use the exact HTTPS address printed by Serve. It is
private to devices permitted on your tailnet. Do not use Tailscale Funnel
or port forwarding to expose music publicly.

On Android, install the Tailscale app, sign in to the SAME tailnet as Fedora,
and connect the VPN. Do not exclude Moka (com.mokamusic.player) from Tailscale
in Android app-based split tunneling. Test the HTTPS URL in the Android browser
with Wi-Fi disabled to verify that cellular access works. An active competing
VPN can block Tailscale.

In Moka, go to Settings → Network Music, enter the Serve HTTPS URL (without
/rest), the Navidrome username, and password. Tap Connect and optionally
Remember login; the password is stored encrypted in Android Keystore.
Disconnect retains your saved login; Forget server clears credentials.

Browse using Library → Network · Navidrome, then Tracks, Albums, Artists,
or Genres. Search server tracks on demand, browse artist albums, and load
genre tracks in pages. Browse-all Tracks loads a few albums per batch rather
than fetching the entire server catalog.

The app requires a trusted HTTPS URL. Do not use plain HTTP localhost or
raw 100.x.y.z Tailscale IPs without a matching TLS certificate.

## 6. Troubleshooting

~~~bash
tailscale status
tailscale serve status
curl -I http://127.0.0.1:4533
sudo podman logs --tail 70 moka-navidrome
~~~

If Navidrome was created rootlessly, remove sudo from podman commands.
If Android Chrome cannot open the HTTPS address on cellular, check the
Tailscale app, Serve status, MagicDNS, ACL permissions, and server connectivity
before changing Moka.

## More troubleshooting

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
