#!/bin/bash

# Usage: ./ping-port.sh <ip> <port> [interval_seconds]
# Example: ./ping-port.sh 192.168.2.183 18080

IP=$1
PORT=$2
INTERVAL=${3:-120}  # Default 120 seconds (2 minutes)

# Validate arguments
if [ -z "$IP" ] || [ -z "$PORT" ]; then
    echo "Usage: $0 <ip> <port> [interval_seconds]"
    echo "Example: $0  192.168.2.183 18080 120"
    exit 1
fi

echo "Starting ping to $IP:$PORT every $INTERVAL seconds..."
echo "Press Ctrl+C to stop"

while true; do
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    if nc -zv -w 5 "$IP" "$PORT" 2>&1 | grep -q "succeeded"; then
        echo "[$TIMESTAMP] $IP:$PORT - Connected"
    else
        echo "[$TIMESTAMP] $IP:$PORT - Failed to connect"
    fi
    sleep "$INTERVAL"
done
