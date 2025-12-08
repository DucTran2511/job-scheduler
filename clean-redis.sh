#!/bin/bash

# Redis Cleanup Script
# This script cleans all data from Redis (streams, consumer groups, etc.)

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

CONTAINER_NAME="job_redis"

echo -e "${CYAN}=========================================="
echo -e "  REDIS CLEANUP SCRIPT"
echo -e "==========================================${NC}"
echo ""

# Check if container is running
echo -e "${BLUE}Checking Redis connection...${NC}"
if docker ps --filter "name=${CONTAINER_NAME}" --format "{{.Names}}" | grep -q "${CONTAINER_NAME}"; then
    echo -e "${GREEN}✓ Redis container is running${NC}"
else
    echo -e "${RED}✗ Container '${CONTAINER_NAME}' is not running${NC}"
    echo -e "${YELLOW}Start it with: docker-compose up -d redis${NC}"
    exit 1
fi
echo ""

# Ask for confirmation
echo -e "${RED}⚠️  WARNING: This will delete ALL data from Redis!${NC}"
echo -e "${YELLOW}This includes streams, consumer groups, and all keys.${NC}"
echo -e "${YELLOW}Are you sure you want to continue? (yes/no)${NC}"
read -p "> " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    echo -e "${YELLOW}Cleanup cancelled.${NC}"
    exit 0
fi
echo ""

echo -e "${BLUE}Starting Redis cleanup...${NC}"
echo ""

# Flush all Redis data
echo -e "${YELLOW}Flushing all Redis data...${NC}"
docker exec "${CONTAINER_NAME}" redis-cli FLUSHALL
echo -e "${GREEN}✓ All Redis data cleared${NC}"
echo ""

echo -e "${GREEN}✓ Redis cleanup completed successfully!${NC}"
echo ""
echo -e "${CYAN}=========================================="
echo -e "  CLEANUP COMPLETE"
echo -e "==========================================${NC}"
echo ""
echo -e "${YELLOW}Note: You need to restart both services:${NC}"
echo -e "  1. Stop API service (Ctrl+C)"
echo -e "  2. Stop Worker service (Ctrl+C)"
echo -e "  3. Restart both services"
echo ""

