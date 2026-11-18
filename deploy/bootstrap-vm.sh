#!/usr/bin/env bash
# 새 오라클 VM(Ubuntu 24.04 aarch64)을 배포할 수 있는 상태로 만든다(결정 020). VM 에서 한 번: sudo ./bootstrap-vm.sh <staging|prod>
# 여러 번 돌려도 결과가 같다. SSH 설정은 건드리지 않는다(오라클 이미지가 이미 키 로그인만 허용한다).
set -euo pipefail

ENV_NAME="${1:-}"
case "$ENV_NAME" in staging | prod) ;; *) echo "사용법: sudo $0 <staging|prod>" >&2; exit 2 ;; esac
[ "$(id -u)" -eq 0 ] || { echo "sudo 로 돌린다." >&2; exit 2; }

APP_DIR=/opt/byeolmyeong
APP_USER=ubuntu
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) event=$*"; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q iptables-persistent unattended-upgrades curl

# 1. 방화벽: 오라클 Ubuntu 이미지의 iptables 는 22 만 열고 나머지를 REJECT 한다. REJECT 규칙 앞에 80·443 을 넣는다.
#    (콘솔의 보안 목록에서도 80·443 을 열어야 한다. docs/deploy.md)
#    Docker 를 설치하기 전에, 그리고 바뀐 것이 있을 때만 저장한다. Docker 가 돌 때 저장하면 Docker 의 규칙까지 굳어 재부팅 뒤 꼬인다.
changed=0
for port in 80 443; do
	if ! iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2> /dev/null; then
		reject_line="$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')"
		if [ -n "$reject_line" ]; then
			iptables -I INPUT "$reject_line" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
		else
			iptables -A INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT
		fi
		changed=1
	fi
done
if [ "$changed" -eq 1 ]; then
	if systemctl is-active --quiet docker; then
		log "bootstrap_warning step=firewall reason=docker_running_rules_not_saved"
	else
		netfilter-persistent save
	fi
fi

# 2. Docker: Ubuntu 저장소의 꾸러미로 설치하고 부팅 때 켜지게 한다.
apt-get install -y -q docker.io docker-compose-v2
systemctl enable --now docker
usermod -aG docker "$APP_USER"

# 3. 배포 디렉터리와 시간대.
install -d -o "$APP_USER" -g "$APP_USER" -m 750 "$APP_DIR" "$APP_DIR/backups"
timedatectl set-timezone Asia/Seoul

# 4. 자동 보안 업데이트(Ubuntu 기본값이 켜져 있는지 확인만 하고, 꺼져 있으면 켠다).
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

# 5. 운영만: 매일 04:00(KST) 백업. 출력은 backups/backup.log 에 쌓는다.
CRON_LINE="0 4 * * * $APP_DIR/backup.sh >> $APP_DIR/backups/backup.log 2>&1"
if [ "$ENV_NAME" = prod ]; then
	( crontab -u "$APP_USER" -l 2> /dev/null | grep -vF "$APP_DIR/backup.sh" || true; echo "$CRON_LINE" ) | crontab -u "$APP_USER" -
fi

log "bootstrap_done env=$ENV_NAME docker=$(docker --version | awk '{ print $3 }' | tr -d ,)"
