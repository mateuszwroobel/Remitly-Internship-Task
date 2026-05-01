#!/bin/bash
set -e

if [ -z "$1" ]; then
    PORT=8080
else
    PORT=$1
fi

export PORT

echo "Building and starting Docker Compose cluster on port $PORT..."
docker compose up --build -d

echo ""
echo "=========================================================="
echo "🚀 Stock Exchange Cluster is running inside Docker!"
echo "📡 Load Balancer is available at: http://localhost:$PORT"
echo "🖧  3 Backend Nodes are peering internally."
echo "🛑 To stop the cluster: docker compose down"
echo "📋 To view live logs: docker compose logs -f"
echo "=========================================================="
