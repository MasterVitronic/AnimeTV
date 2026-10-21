#!/bin/sh
# Comprueba si los mirrors de AnimeKai sirven el sitio real (no un parking).
for h in anikai.sbs anikai.vip anikai.lol anikai.xyz anikai.net anikai.org anikai.site anikai.online anikai.store anikai.me; do
  curl -s -L --max-time 12 -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/133.0.0.0" \
    "https://$h/browser?keyword=naruto" -o /tmp/anikai-probe.html -w "%{http_code} %{size_download}" >/tmp/anikai-code.txt 2>/dev/null
  title=$(grep -oE "<title>[^<]+" /tmp/anikai-probe.html 2>/dev/null | head -1)
  printf "%-16s %s %s\n" "$h" "$(cat /tmp/anikai-code.txt)" "$title"
done
