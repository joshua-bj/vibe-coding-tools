#!/bin/bash

# Generate self-signed certificate for local development
# Usage: ./scripts/generate-certs.sh [IP_ADDRESS]

IP=${1:-192.168.31.250}  # Default IP, override with argument

if [ "$1" != "" ]; then
  IP=$1
fi

echo "Generating self-signed certificate for IP: $IP"

# Create certs directory if it doesn't exist
mkdir -p certs

# Generate self-signed certificate
openssl req -x509 -newkey rsa:2048 \
  -keyout certs/server.key \
  -out certs/server.crt \
  -days 365 -nodes \
  -subj "/C=CN/ST=Beijing/L=Beijing/O=WeChat Scanner Demo/OU=Development/CN=$IP"

# Set appropriate permissions
chmod 600 certs/server.key
chmod 644 certs/server.crt

echo ""
echo "Certificates generated successfully!"
echo "  - Certificate: certs/server.crt"
echo "  - Private Key: certs/server.key"
echo ""
echo "Note: These are self-signed certificates for development only."
echo "      Browsers will show security warnings - this is normal."
