#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl git openssh-server unzip openjdk-17-jdk-headless openjdk-21-jre-headless iptables

if ! id jenkins-agent >/dev/null 2>&1; then
  useradd --create-home --home-dir /var/lib/jenkins-agent --shell /bin/bash jenkins-agent
fi
install -d -m 0700 -o jenkins-agent -g jenkins-agent /var/lib/jenkins-agent/.ssh
install -d -m 0755 /opt/android-sdk/cmdline-tools

if [ ! -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  if [ -e /opt/android-sdk/cmdline-tools/latest ]; then
    printf '%s\n' 'Incomplete Android tools exist; refusing to overwrite them.' >&2
    exit 1
  fi
  tools_archive="$(mktemp --suffix=.zip)"
  tools_directory="$(mktemp -d)"
  trap 'rm -f "$tools_archive"; rm -rf "$tools_directory"' EXIT
  curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
    https://dl.google.com/android/repository/commandlinetools-linux-12266719_latest.zip \
    -o "$tools_archive"
  unzip -q "$tools_archive" -d "$tools_directory"
  mv "$tools_directory/cmdline-tools" /opt/android-sdk/cmdline-tools/latest
fi

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
sdkmanager=/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager
printf 'y\n%.0s' {1..20} | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
chmod -R a+rX /opt/android-sdk

cat > /etc/ssh/sshd_config.d/60-jenkins-agent.conf <<'EOF'
PasswordAuthentication no
PermitRootLogin no
AllowAgentForwarding no
AllowTcpForwarding no
X11Forwarding no
EOF
printf '%s\n' 'd /run/sshd 0755 root root -' > /etc/tmpfiles.d/jenkins-agent-sshd.conf
systemd-tmpfiles --create /etc/tmpfiles.d/jenkins-agent-sshd.conf
sshd -t
systemctl enable ssh
systemctl restart ssh

cat > /usr/local/sbin/jenkins-agent-metadata-isolation <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
uid="$(id -u jenkins-agent)"
iptables -C OUTPUT -m owner --uid-owner "$uid" -d 169.254.169.254 -j REJECT 2>/dev/null \
  || iptables -A OUTPUT -m owner --uid-owner "$uid" -d 169.254.169.254 -j REJECT
ip6tables -C OUTPUT -m owner --uid-owner "$uid" -d fd00:ec2::254 -j REJECT 2>/dev/null \
  || ip6tables -A OUTPUT -m owner --uid-owner "$uid" -d fd00:ec2::254 -j REJECT
EOF
chmod 0755 /usr/local/sbin/jenkins-agent-metadata-isolation
cat > /etc/systemd/system/jenkins-agent-metadata-isolation.service <<'EOF'
[Unit]
Description=Keep Android builds away from instance credentials
After=network.target
Before=ssh.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/jenkins-agent-metadata-isolation
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now jenkins-agent-metadata-isolation
printf '%s\n' 'Agent tools installed. Authorize the controller public SSH key before connecting the Jenkins node.'
shutdown -h +1
