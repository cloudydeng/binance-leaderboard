#!/usr/bin/env bash
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo '请用 root 在服务器运行此脚本' >&2
  exit 1
fi

read -r -s -p '币安广场 Posting API Key（输入不回显）: ' square_key
printf '\n'
if [[ -z "$square_key" || "$square_key" == *[[:space:]\"\\]* ]]; then
  echo '密钥为空或包含不支持的空白、引号、反斜杠' >&2
  exit 1
fi

install -d -m 0700 /etc/binance-leaderboard
umask 077
tmp_file="$(mktemp /etc/binance-leaderboard/square.env.XXXXXX)"
trap 'rm -f "$tmp_file"' EXIT
printf 'BINANCE_SQUARE_OPENAPI_KEY="%s"\n' "$square_key" > "$tmp_file"
chmod 0600 "$tmp_file"
mv -f "$tmp_file" /etc/binance-leaderboard/square.env
trap - EXIT
unset square_key
echo '广场发布密钥已保存；下一次完整定时报告会自动发帖。'
