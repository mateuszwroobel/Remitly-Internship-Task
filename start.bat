@echo off

set PORT=%1
if "%PORT%"=="" set PORT=8080

echo Building and starting Docker Compose cluster on port %PORT%...
docker compose up --build -d

echo.
echo ==========================================================
echo [ROCKET] Stock Exchange Cluster is running inside Docker!
echo [ANTENNA] Load Balancer is available at: http://localhost:%PORT%
echo [NETWORK] 3 Backend Nodes are peering internally.
echo [STOP] To stop the cluster: docker compose down
echo [LOGS] To view live logs: docker compose logs -f
echo ==========================================================
