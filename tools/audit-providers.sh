#!/bin/sh
# Comprueba la disponibilidad HTTP de los hosts usados por los proveedores.
# Uso: sh tools/audit-providers.sh
HOSTS="
https://senshi.live
https://epeng.animeapps.top
https://anikai.cc
https://www1.anikai.cc
https://www2.anikai.cc
https://www3.anikai.cc
https://www4.anikai.cc
https://megaplay.buzz
https://anikototv.to
https://hianimes.re
https://reanime.to
https://anizone.to
https://www.animegg.org
https://anineko.to
https://2dhive.com
https://mapper.miruro.tv
https://dio.miruro.tv
https://prxy.miruro.to
https://www.miruro.tv
"

for u in $HOSTS; do
  out=$(curl -s -o /dev/null -L --max-time 15 -w "%{http_code} %{time_total}" "$u" 2>/dev/null)
  printf "%-30s %s\n" "$u" "$out"
done
