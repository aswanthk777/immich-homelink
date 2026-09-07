#!/bin/bash
emb=$(docker exec immich_postgres psql -U postgres -d immich -tAc 'select count(*) from smart_search')
faces=$(docker exec immich_postgres psql -U postgres -d immich -tAc 'select count(*) from asset_face')
assets=$(docker exec immich_postgres psql -U postgres -d immich -tAc 'select count(*) from asset where "deletedAt" is null')
q=""; for x in smartSearch faceDetection facialRecognition; do q="$q $x=$(docker exec immich_redis valkey-cli llen immich_bull:$x:wait)/$(docker exec immich_redis valkey-cli llen immich_bull:$x:active)"; done
err=$(docker logs --since 35s immich_server 2>&1 | grep -c "ERROR")
mlerr=$(docker logs --since 35s immich_machine_learning 2>&1 | grep -cE "ERROR|WARNING")
load=$(cut -d" " -f1 /proc/loadavg); ram=$(free -m | awk '/Mem:/{print $3"/"$2}'); t=$(vcgencmd measure_temp | tr -dc "0-9.")
thr=$(vcgencmd get_throttled | cut -d= -f2)
cpu=$(docker stats --no-stream --format "{{.Name}}={{.CPUPerc}} mem={{.MemUsage}}" immich_machine_learning immich_server | sed 's/immich_//' | tr "\n" " ")
cpuall=$(top -bn2 -d1 | grep "^%Cpu" | tail -1 | awk '{printf "cpu_busy=%.0f%%", 100-$8}')
echo "emb=$emb faces=$faces assets=$assets | wait/active:$q | load=$load $cpuall ram=${ram}MB temp=${t}C throttled=$thr | $cpu| srvErr=$err mlErr=$mlerr"
