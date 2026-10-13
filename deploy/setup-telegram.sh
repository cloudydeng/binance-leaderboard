#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo '请以 root 运行此脚本。' >&2
  exit 1
fi

jar=/opt/binance-leaderboard/target/binance-leaderboard-stat.jar
config_dir=/etc/binance-leaderboard
config_file=$config_dir/telegram.env

if [[ ! -f $jar ]]; then
  echo '未找到已部署的榜单程序。' >&2
  exit 1
fi

echo '请先在 BotFather 为 @cloudy_binance_bot 重新生成 Token，并向机器人发送 /start。'
read -r -s -p '粘贴新的 Bot Token（输入不显示）：' TELEGRAM_BOT_TOKEN
printf '\n'
export TELEGRAM_BOT_TOKEN

if [[ ! $TELEGRAM_BOT_TOKEN =~ ^[0-9]+:[A-Za-z0-9_-]+$ ]]; then
  echo 'Token 格式无效。' >&2
  exit 1
fi

updates=$(java -jar "$jar" --telegram-chat-id) || {
  echo '查询 Chat ID 失败。请确认 Token 属于该机器人且机器人已收到 /start。' >&2
  exit 1
}
mapfile -t chat_ids < <(printf '%s\n' "$updates" | sed -n 's/^Chat ID: //p')
if (( ${#chat_ids[@]} == 0 )); then
  echo '尚未收到机器人消息。请向 @cloudy_binance_bot 发送 /start 后重新运行。' >&2
  exit 1
fi

echo '可用 Chat ID：'
printf '  %s\n' "${chat_ids[@]}"
if (( ${#chat_ids[@]} == 1 )); then
  TELEGRAM_CHAT_ID=${chat_ids[0]}
else
  read -r -p '输入你希望接收通知的 Chat ID：' TELEGRAM_CHAT_ID
fi
if [[ ! $TELEGRAM_CHAT_ID =~ ^-?[0-9]+$ ]]; then
  echo 'Chat ID 必须是上方显示的数字。' >&2
  exit 1
fi
found=false
for candidate in "${chat_ids[@]}"; do
  if [[ $candidate == "$TELEGRAM_CHAT_ID" ]]; then found=true; break; fi
done
if [[ $found != true ]]; then
  echo 'Chat ID 不在机器人收到的消息中。' >&2
  exit 1
fi

install -d -m 0700 "$config_dir"
umask 077
temporary=$(mktemp "$config_dir/.telegram.env.XXXXXX")
trap 'rm -f "$temporary"' EXIT
printf 'TELEGRAM_BOT_TOKEN=%s\nTELEGRAM_CHAT_ID=%s\n' "$TELEGRAM_BOT_TOKEN" "$TELEGRAM_CHAT_ID" > "$temporary"
chmod 0600 "$temporary"
mv -f "$temporary" "$config_file"
unset TELEGRAM_BOT_TOKEN
systemctl restart binance-leaderboard
systemctl is-active --quiet binance-leaderboard
echo '机器人凭据已配置，榜单服务已重启。'
