#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT_DIR"

JAR="${JAR:-target/bcastnode-1.0-SNAPSHOT.jar}"

if [[ $# -lt 2 ]]; then
  echo "Usage:"
  echo "  ./startup.sh <config.txt> all"
  echo "  ./startup.sh <config.txt> <startId> <endId>"
  exit 2
fi

CONFIG="$1"
MODE="$2"

[[ -f "$JAR" ]] || { echo "ERROR: Jar not found: $JAR"; exit 2; }
[[ -f "$CONFIG" ]] || { echo "ERROR: Config not found: $CONFIG"; exit 2; }

mkdir -p runlogs

# Extract node IDs from config:
# - skip first line
# - strip CR
# - strip comments (# ...)
# - ignore blank lines
# - take 3rd field as id (require it)
mapfile -t IDS < <(
  tail -n +2 "$CONFIG" \
    | tr -d '\r' \
    | sed 's/#.*$//' \
    | awk 'NF>=3 {print $3}' \
    | sort -n
)

if [[ ${#IDS[@]} -eq 0 ]]; then
  echo "ERROR: No node ids found (expected: ip port id lines)"
  exit 2
fi

if [[ "$MODE" == "all" ]]; then
  START_ID="${IDS[0]}"
  END_ID="${IDS[-1]}"
else
  [[ $# -ge 3 ]] || { echo "Usage: ./startup.sh <config.txt> <startId> <endId>"; exit 2; }
  START_ID="$MODE"
  END_ID="$3"
fi

echo "Config=$CONFIG"
echo "Jar=$JAR"
echo "Node ids in config: ${IDS[*]}"
echo "Launching ids in range $START_ID..$END_ID"

for id in "${IDS[@]}"; do
  if (( id < START_ID || id > END_ID )); then
    echo "Skipping $id (not in range)"
    continue
  fi

  echo "Starting node id $id"
  java -DNODE_INDEX="$id" -jar "$JAR" "$CONFIG" "$id" &
done

wait
