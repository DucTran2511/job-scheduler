#!/bin/bash

# Database Visualization Script (Docker Version)
# This script connects to PostgreSQL via Docker and displays all data in a formatted way

set -e

# Color codes for better visualization
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m' # No Color

# Database configuration
CONTAINER_NAME="job-scheduler-db"
DB_NAME="jobdb"
DB_USER="jobuser"
DB_PASSWORD="jobpass"

echo -e "${CYAN}=========================================="
echo -e "  DATABASE VISUALIZATION"
echo -e "==========================================${NC}"
echo ""
echo -e "${WHITE}Database: ${GREEN}${DB_NAME}${NC}"
echo -e "${WHITE}Container: ${GREEN}${CONTAINER_NAME}${NC}"
echo -e "${WHITE}User: ${GREEN}${DB_USER}${NC}"
echo ""

# Function to print section header
print_header() {
    echo ""
    echo -e "${MAGENTA}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${MAGENTA}║${NC} ${YELLOW}$1${NC}"
    echo -e "${MAGENTA}╚════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# Function to execute and display query
execute_query() {
    local query=$1
    docker exec -e PGPASSWORD="${DB_PASSWORD}" "${CONTAINER_NAME}" \
        psql -U "${DB_USER}" -d "${DB_NAME}" \
        -c "${query}" \
        --pset=border=2 \
        --pset=format=aligned
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

# ============================================
# 1. WORKFLOW_RUNS TABLE
# ============================================
print_header "📋 WORKFLOW_RUNS TABLE"

WORKFLOW_RUNS_COUNT=$(get_count "workflow_runs")
echo -e "${WHITE}Total Records: ${GREEN}${WORKFLOW_RUNS_COUNT}${NC}"
echo ""

if [ "$WORKFLOW_RUNS_COUNT" -gt 0 ]; then
    execute_query "
    SELECT
        id,
        workflow_id,
        status,
        started_at,
        finished_at
    FROM workflow_runs
    ORDER BY started_at DESC
    LIMIT 20;"
else
    echo -e "${YELLOW}No workflow runs found${NC}"
fi

# ============================================
# 2. TASK_RUNS TABLE
# ============================================
print_header "🔧 TASK_RUNS TABLE"

TASK_RUNS_COUNT=$(get_count "task_runs")
echo -e "${WHITE}Total Records: ${GREEN}${TASK_RUNS_COUNT}${NC}"
echo ""

if [ "$TASK_RUNS_COUNT" -gt 0 ]; then
    execute_query "
    SELECT
        id,
        workflow_run_id,
        task_id,
        task_name,
        status,
        started_at,
        finished_at,
        retry_count,
        max_retries,
        last_error
    FROM task_runs
    ORDER BY started_at DESC
    LIMIT 20;"
else
    echo -e "${YELLOW}No task runs found${NC}"
fi

# ============================================
# 3. WORKFLOW_ENTITY TABLE
# ============================================
print_header "📝 WORKFLOWS TABLE"

WORKFLOW_ENTITY_COUNT=$(get_count "workflows")
echo -e "${WHITE}Total Records: ${GREEN}${WORKFLOW_ENTITY_COUNT}${NC}"
echo ""

if [ "$WORKFLOW_ENTITY_COUNT" -gt 0 ]; then
    execute_query "
    SELECT *
    FROM workflows
    ORDER BY id DESC
    LIMIT 20;"
else
    echo -e "${YELLOW}No workflows found${NC}"
fi

# ============================================
# 4. JOB_ENTITY TABLE (skip if doesn't exist)
# ============================================
# Skipping job_entity as it may not exist

# ============================================
# 5. STATISTICS SUMMARY
# ============================================
print_header "📊 STATISTICS SUMMARY"

execute_query "
SELECT
    'Total Workflow Runs' AS metric,
    COUNT(*) AS value
FROM workflow_runs
UNION ALL
SELECT
    'Running Workflows',
    COUNT(*)
FROM workflow_runs
WHERE status = 'RUNNING'
UNION ALL
SELECT
    'Completed Workflows',
    COUNT(*)
FROM workflow_runs
WHERE status = 'COMPLETED'
UNION ALL
SELECT
    'Failed Workflows',
    COUNT(*)
FROM workflow_runs
WHERE status = 'FAILED'
UNION ALL
SELECT
    'Total Task Runs',
    COUNT(*)
FROM task_runs
UNION ALL
SELECT
    'Running Tasks',
    COUNT(*)
FROM task_runs
WHERE status = 'RUNNING'
UNION ALL
SELECT
    'Completed Tasks',
    COUNT(*)
FROM task_runs
WHERE status = 'SUCCESS'
UNION ALL
SELECT
    'Failed Tasks',
    COUNT(*)
FROM task_runs
WHERE status = 'FAILED'
UNION ALL
SELECT
    'Pending Tasks',
    COUNT(*)
FROM task_runs
WHERE status = 'PENDING';
"

# ============================================
# 6. RECENT ACTIVITY
# ============================================
print_header "🕒 RECENT ACTIVITY (Last 10 workflows)"

execute_query "
SELECT
    wr.id AS workflow_run_id,
    wr.status AS workflow_status,
    wr.started_at,
    COUNT(tr.id) AS total_tasks,
    COUNT(CASE WHEN tr.status = 'SUCCESS' THEN 1 END) AS completed_tasks,
    COUNT(CASE WHEN tr.status = 'FAILED' THEN 1 END) AS failed_tasks,
    CASE
        WHEN wr.finished_at IS NOT NULL
        THEN EXTRACT(EPOCH FROM (wr.finished_at - wr.started_at))::INTEGER || ' seconds'
        ELSE 'Running'
    END AS duration
FROM workflow_runs wr
LEFT JOIN task_runs tr ON tr.workflow_run_id = wr.id
GROUP BY wr.id, wr.status, wr.started_at, wr.finished_at
ORDER BY wr.started_at DESC
LIMIT 10;
"

# ============================================
# 7. FAILED TASKS DETAILS
# ============================================
print_header "❌ FAILED TASKS (with errors)"

FAILED_COUNT=$(docker exec -e PGPASSWORD="${DB_PASSWORD}" "${CONTAINER_NAME}" \
    psql -U "${DB_USER}" -d "${DB_NAME}" \
    -t -c "SELECT COUNT(*) FROM task_runs WHERE status = 'FAILED';" 2>/dev/null | xargs || echo "0")

if [ "$FAILED_COUNT" -gt 0 ]; then
    execute_query "
    SELECT
        task_id,
        task_name,
        workflow_run_id,
        retry_count,
        max_retries,
        last_error,
        started_at,
        finished_at
    FROM task_runs
    WHERE status = 'FAILED'
    ORDER BY finished_at DESC
    LIMIT 10;
    "
else
    echo -e "${GREEN}No failed tasks found! 🎉${NC}"
fi

# ============================================
# 8. WORKFLOW WITH TASK DETAILS
# ============================================
print_header "🔗 WORKFLOWS WITH TASK BREAKDOWN"

execute_query "
SELECT
    wr.id AS workflow_id,
    wr.status AS workflow_status,
    COUNT(tr.id) AS total_tasks_in_db,
    COUNT(CASE WHEN tr.status = 'SUCCESS' THEN 1 END) AS completed,
    COUNT(CASE WHEN tr.status = 'RUNNING' THEN 1 END) AS running,
    COUNT(CASE WHEN tr.status = 'FAILED' THEN 1 END) AS failed,
    COUNT(CASE WHEN tr.status = 'PENDING' THEN 1 END) AS pending,
    wr.started_at
FROM workflow_runs wr
LEFT JOIN task_runs tr ON tr.workflow_run_id = wr.id
GROUP BY wr.id, wr.status, wr.started_at
ORDER BY wr.started_at DESC
LIMIT 10;
"

# ============================================
# 9. ALL TABLES IN DATABASE
# ============================================
print_header "📚 ALL TABLES IN DATABASE"

execute_query "
SELECT
    schemaname,
    tablename,
    tableowner
FROM pg_catalog.pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY tablename;
"

# ============================================
# 10. TABLE SIZES
# ============================================
print_header "💾 TABLE SIZES"

execute_query "
SELECT
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_catalog.pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
"

# Clean up
unset PGPASSWORD

echo ""
echo -e "${CYAN}=========================================="
echo -e "  VISUALIZATION COMPLETE"
echo -e "==========================================${NC}"
echo ""
echo -e "${YELLOW}Tip: Run this script anytime to see current database state${NC}"
echo -e "${YELLOW}Usage: ./visualize-database.sh${NC}"
echo ""
