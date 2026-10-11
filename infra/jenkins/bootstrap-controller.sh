#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl fontconfig git openjdk-21-jre-headless python3-boto3
install -d -m 0755 /etc/apt/keyrings
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key \
  -o /etc/apt/keyrings/jenkins-keyring.asc
printf '%s\n' 'deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/' \
  > /etc/apt/sources.list.d/jenkins.list
apt-get update
apt-get install -y "jenkins=${JENKINS_VERSION:?Set the verified Jenkins LTS version}"
systemctl stop jenkins
apt-mark hold jenkins

install -d -m 0755 /etc/systemd/system/jenkins.service.d
cat > /etc/systemd/system/jenkins.service.d/pilot.conf <<'EOF'
[Service]
Environment="JAVA_OPTS=-Djava.awt.headless=true -Xms256m -Xmx768m"
Environment="JENKINS_OPTS=--httpListenAddress=127.0.0.1 --httpPort=8080"
EOF

install -d -m 0750 -o jenkins -g jenkins /var/lib/jenkins/init.groovy.d
cat > /var/lib/jenkins/init.groovy.d/01-controller-isolation.groovy <<'EOF'
import jenkins.model.Jenkins

def controller = Jenkins.get()
controller.setNumExecutors(0)
controller.setSlaveAgentPort(-1)
controller.save()
EOF
chown jenkins:jenkins /var/lib/jenkins/init.groovy.d/01-controller-isolation.groovy
chmod 0640 /var/lib/jenkins/init.groovy.d/01-controller-isolation.groovy

install -d -m 0700 -o jenkins -g jenkins /var/lib/jenkins/.ssh
if [ ! -e /var/lib/jenkins/.ssh/id_ed25519 ]; then
  runuser -u jenkins -- ssh-keygen -q -t ed25519 -N '' -C 'solventa-jenkins-agent' \
    -f /var/lib/jenkins/.ssh/id_ed25519
fi

systemctl daemon-reload
systemctl enable --now jenkins
printf '%s\n' 'Controller installation complete. Finish setup through a private Session Manager tunnel.'
