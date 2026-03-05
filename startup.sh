#!/usr/bin/env bash
set -euo pipefail

# Ensure working directory is the project root (directory of this script)
ROOT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT_DIR"

JAR="${JAR:-target/bcastnode-1.0-SNAPSHOT.jar}"

if [[ $# -lt 2 ]]; then
  echo "Usage:"
  echo "  ./startup.sh <config.txt> all"
  echo "  ./startup.sh <config.txt> <startIdx> <endIdx>"
  exit 2
fi

CONFIG="$1"
MODE="$2"

[[ -f "$JAR" ]] || { echo "ERROR: Jar not found: $JAR"; exit 2; }
[[ -f "$CONFIG" ]] || { echo "ERROR: Config not found: $CONFIG"; exit 2; }

M=$(
  tail -n +2 "$CONFIG" |
    tr -d '\r' |
    sed 's/#.*$//' |
    sed '/^[[:space:]]*$/d' |
    wc -l | tr -d ' '
)

if [[ "$MODE" == "all" ]]; then
  START=0
  END=$((M - 1))
else
  [[ $# -ge 3 ]] || { echo "Usage: ./startup.sh <config.txt> <startIdx> <endIdx>"; exit 2; }
  START="$MODE"
  END="$3"
fi

echo "Config=$CONFIG"
echo "Jar=$JAR"
echo "Nodes in config (M)=$M"
echo "Launching indices $START..$END"

for ((i=START; i<=END; i++)); do
  if (( i < 0 || i >= M )); then
    echo "Skipping $i (out of range)"
    continue
  fi

  echo "Starting node $i"
  java -DNODE_INDEX="$i" -jar "$JAR" "$CONFIG" "$i" &
done

wait
