#!/bin/bash

# Database Cleanup Script
# This script cleans all data from the database tables

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m' # No Color

# Database configuration
CONTAINER_NAME="job_postgres"
DB_NAME="jobdb"
DB_USER="jobuser"
DB_PASSWORD="jobpass"

echo -e "${CYAN}=========================================="
echo -e "  DATABASE CLEANUP SCRIPT"
echo -e "==========================================${NC}"
echo ""
echo -e "${WHITE}Database: ${GREEN}${DB_NAME}${NC}"
echo -e "${WHITE}Container: ${GREEN}${CONTAINER_NAME}${NC}"
echo ""

# Function to execute query
execute_query() {
    local query=$1
    docker exec -e PGPASSWORD="${DB_PASSWORD}" "${CONTAINER_NAME}" \
        psql -U "${DB_USER}" -d "${DB_NAME}" \
        -c "${query}"
}

# Function to get count
get_count() {
    local table=$1
    docker exec -e PGPASSWORD="${DB_PASSWORD}" "${CONTAINER_NAME}" \
        psql -U "${DB_USER}" -d "${DB_NAME}" \
        -t -c "SELECT COUNT(*) FROM ${table};" 2>/dev/null | xargs || echo "0"
}

# Check if container is running
echo -e "${BLUE}Checking database connection...${NC}"
if docker ps --filter "name=${CONTAINER_NAME}" --format "{{.Names}}" | grep -q "${CONTAINER_NAME}"; then
    echo -e "${GREEN}✓ Database container is running${NC}"
else
    echo -e "${RED}✗ Container '${CONTAINER_NAME}' is not running${NC}"
    echo -e "${YELLOW}Start it with: docker-compose up -d postgres${NC}"
    exit 1
fi

# Test database connection
if docker exec -e PGPASSWORD="${DB_PASSWORD}" "${CONTAINER_NAME}" \
    psql -U "${DB_USER}" -d "${DB_NAME}" -c '\q' 2>/dev/null; then
    echo -e "${GREEN}✓ Database connection successful${NC}"
else
    echo -e "${RED}✗ Cannot connect to database${NC}"
    exit 1
fi
echo ""

# Show current data counts
echo -e "${YELLOW}Current data in database:${NC}"
echo -e "${WHITE}Workflow Runs: ${GREEN}$(get_count "workflow_runs")${NC}"
echo -e "${WHITE}Task Runs: ${GREEN}$(get_count "task_runs")${NC}"
echo -e "${WHITE}Workflows: ${GREEN}$(get_count "workflows")${NC}"
echo -e "${WHITE}Tasks: ${GREEN}$(get_count "tasks")${NC}"
echo -e "${WHITE}Jobs: ${GREEN}$(get_count "jobs")${NC}"
echo -e "${WHITE}Task Dependencies: ${GREEN}$(get_count "task_dependencies")${NC}"
echo ""

# Ask for confirmation
echo -e "${RED}⚠️  WARNING: This will delete ALL data from the database!${NC}"
echo -e "${YELLOW}Are you sure you want to continue? (yes/no)${NC}"
read -p "> " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    echo -e "${YELLOW}Cleanup cancelled.${NC}"
    exit 0
fi
echo ""

echo -e "${BLUE}Starting cleanup...${NC}"
echo ""

# Clean tables in correct order (respecting foreign key constraints)
echo -e "${YELLOW}1. Cleaning task_runs...${NC}"
execute_query "TRUNCATE TABLE task_runs CASCADE;"
echo -e "${GREEN}✓ task_runs cleaned${NC}"
echo ""

echo -e "${YELLOW}2. Cleaning workflow_runs...${NC}"
execute_query "TRUNCATE TABLE workflow_runs CASCADE;"
echo -e "${GREEN}✓ workflow_runs cleaned${NC}"
echo ""

echo -e "${YELLOW}3. Cleaning task_dependencies...${NC}"
execute_query "TRUNCATE TABLE task_dependencies CASCADE;"
echo -e "${GREEN}✓ task_dependencies cleaned${NC}"
echo ""

echo -e "${YELLOW}4. Cleaning tasks...${NC}"
execute_query "TRUNCATE TABLE tasks CASCADE;"
echo -e "${GREEN}✓ tasks cleaned${NC}"
echo ""

echo -e "${YELLOW}5. Cleaning workflows...${NC}"
execute_query "TRUNCATE TABLE workflows CASCADE;"
echo -e "${GREEN}✓ workflows cleaned${NC}"
echo ""

echo -e "${YELLOW}6. Cleaning jobs...${NC}"
execute_query "TRUNCATE TABLE jobs CASCADE;"
echo -e "${GREEN}✓ jobs cleaned${NC}"
echo ""

# Verify cleanup
echo -e "${BLUE}Verifying cleanup...${NC}"
TOTAL_RECORDS=0
TOTAL_RECORDS=$((TOTAL_RECORDS + $(get_count "workflow_runs")))
TOTAL_RECORDS=$((TOTAL_RECORDS + $(get_count "task_runs")))
TOTAL_RECORDS=$((TOTAL_RECORDS + $(get_count "workflows")))
TOTAL_RECORDS=$((TOTAL_RECORDS + $(get_count "tasks")))
TOTAL_RECORDS=$((TOTAL_RECORDS + $(get_count "jobs")))
TOTAL_RECORDS=$((TOTAL_RECORDS + $(get_count "task_dependencies")))

echo ""
echo -e "${YELLOW}Final data counts:${NC}"
echo -e "${WHITE}Workflow Runs: ${GREEN}$(get_count "workflow_runs")${NC}"
echo -e "${WHITE}Task Runs: ${GREEN}$(get_count "task_runs")${NC}"
echo -e "${WHITE}Workflows: ${GREEN}$(get_count "workflows")${NC}"
echo -e "${WHITE}Tasks: ${GREEN}$(get_count "tasks")${NC}"
echo -e "${WHITE}Jobs: ${GREEN}$(get_count "jobs")${NC}"
echo -e "${WHITE}Task Dependencies: ${GREEN}$(get_count "task_dependencies")${NC}"
echo ""

if [ "$TOTAL_RECORDS" -eq 0 ]; then
    echo -e "${GREEN}✓ Database cleanup completed successfully!${NC}"
    echo -e "${GREEN}All tables are now empty.${NC}"
else
    echo -e "${RED}✗ Cleanup may not be complete. Total records: ${TOTAL_RECORDS}${NC}"
fi

echo ""
echo -e "${CYAN}=========================================="
echo -e "  CLEANUP COMPLETE"
echo -e "==========================================${NC}"
echo ""

