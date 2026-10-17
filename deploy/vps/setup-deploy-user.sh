#!/usr/bin/env bash
# Sets up the deploy user that GitHub Actions logs in as. Run once on the server, as root, from a
# folder that also holds pulse-deploy:
#   setup-deploy-user.sh "<deploy public key>"
#
# The key can only run pulse-deploy, only from inside the tailnet, and gets no shell, port
# forwarding or terminal. Running the script again replaces the key and the script.
set -euo pipefail
pubkey=${1:?usage: setup-deploy-user.sh "<deploy public key>"}
[[ $pubkey =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+( .*)?$ ]] || { echo "not an ed25519 public key" >&2; exit 2; }

install -m 0755 "$(dirname "$0")/pulse-deploy" /usr/local/sbin/pulse-deploy
install -d -m 0755 /opt/vienna-pulse

id deploy >/dev/null 2>&1 || useradd --system --create-home --home-dir /var/lib/deploy --shell /bin/bash deploy
# No password, but not locked either: sshd refuses key logins to locked accounts.
usermod --password '*' deploy

install -d -m 0700 -o deploy -g deploy /var/lib/deploy/.ssh
# Tailscale gives every device an address in these ranges, so the key is useless from the internet.
printf '%s %s\n' \
  'from="100.64.0.0/10,fd7a:115c:a1e0::/48",restrict,command="sudo -n /usr/local/sbin/pulse-deploy"' \
  "$pubkey" >/var/lib/deploy/.ssh/authorized_keys
chown deploy:deploy /var/lib/deploy/.ssh/authorized_keys
chmod 0600 /var/lib/deploy/.ssh/authorized_keys

# pulse-deploy needs root for Docker. Sudo allows exactly that script, without arguments, and
# passes it the command the key was used with.
cat >/etc/sudoers.d/pulse-deploy <<'SUDOERS'
Defaults!/usr/local/sbin/pulse-deploy env_keep += "SSH_ORIGINAL_COMMAND"
deploy ALL=(root) NOPASSWD: /usr/local/sbin/pulse-deploy ""
SUDOERS
chmod 0440 /etc/sudoers.d/pulse-deploy
visudo --check --file /etc/sudoers.d/pulse-deploy >/dev/null
echo "deploy user ready"
