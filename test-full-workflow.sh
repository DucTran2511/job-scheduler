#!/bin/bash

# Full Workflow Testing Script
# This script tests the complete workflow flow from submission to completion

set -e  # Exit on error

BASE_URL="http://localhost:8080"
WORKFLOW_FILE="api-service/src/main/resources/workflow-samples/daily_backup.yaml"

echo "=========================================="
echo "  FULL WORKFLOW TESTING SCRIPT"
echo "=========================================="
echo ""

# Color codes
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to pretty print JSON (works without jq)
print_json() {
    if command -v jq &> /dev/null; then
        echo "$1" | jq '.'
    elif command -v python3 &> /dev/null; then
        echo "$1" | python3 -m json.tool
    else
        echo "$1"
    fi
}

# Step 1: Check if services are running
echo -e "${BLUE}Step 1: Checking if API service is running...${NC}"
if curl -s -f "${BASE_URL}/actuator/health" > /dev/null; then
    echo -e "${GREEN}✓ API service is running${NC}"
else
    echo -e "${RED}✗ API service is not running. Please start it first.${NC}"
    exit 1
fi
echo ""

# Step 2: Submit a workflow
echo -e "${BLUE}Step 2: Submitting workflow...${NC}"
echo "Reading workflow from: ${WORKFLOW_FILE}"
echo ""

RESPONSE=$(curl -s -X POST "${BASE_URL}/api/workflows/start" \
  -H "Content-Type: text/plain" \
  --data-binary "@${WORKFLOW_FILE}")

echo "Response: ${RESPONSE}"
echo ""

# Extract workflowRunId from response
WORKFLOW_RUN_ID=$(echo $RESPONSE | grep -o '"workflowRunId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$WORKFLOW_RUN_ID" ]; then
    echo -e "${RED}✗ Failed to get workflow run ID${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Workflow submitted successfully${NC}"
echo -e "Workflow Run ID: ${YELLOW}${WORKFLOW_RUN_ID}${NC}"
echo ""

# Step 3: Get workflow status
echo -e "${BLUE}Step 3: Getting workflow status...${NC}"
STATUS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}")
print_json "$STATUS_RESPONSE"
echo ""

# Step 4: Get task details
echo -e "${BLUE}Step 4: Getting task details...${NC}"
TASKS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}/tasks")
print_json "$TASKS_RESPONSE"
echo ""

# Step 5: Wait a bit for worker to potentially pick up tasks
echo -e "${BLUE}Step 5: Waiting 5 seconds for worker to process tasks...${NC}"
sleep 5
echo ""

# Step 6: Check workflow status again
echo -e "${BLUE}Step 6: Checking workflow status again...${NC}"
STATUS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}")
print_json "$STATUS_RESPONSE"
echo ""

# Step 7: Check database
##echo -e "${BLUE}Step 7: Checking database state...${NC}"
#if [ -f "./visualize-database.sh" ]; then
#    echo -e "${YELLOW}Running database visualization...${NC}"
#    ./visualize-database.sh
#else
#    echo -e "${YELLOW}Database visualization script not found, skipping...${NC}"
#fi
#echo ""

# Step 8: Simulate task completion (if worker not running)
echo -e "${YELLOW}Step 8: Manual task completion examples...${NC}"
echo ""
echo "If worker is not running, you can manually complete tasks using these commands:"
echo ""

# Get the first task from workflow
FIRST_TASK="backup_postgres"

echo -e "${BLUE}Example 1: Complete task with SUCCESS (URL params):${NC}"
echo "curl -X POST \"${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}/tasks/${FIRST_TASK}/complete?success=true\""
echo ""

echo -e "${BLUE}Example 2: Complete task with FAILURE (URL params):${NC}"
echo "curl -X POST \"${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}/tasks/${FIRST_TASK}/complete?success=false\" \\"
echo "  -H \"Content-Type: text/plain\" \\"
echo "  -d \"Error message here\""
echo ""

echo -e "${BLUE}Example 3: Complete task with SUCCESS (JSON body):${NC}"
echo "curl -X POST \"${BASE_URL}/api/workflows/task/callback\" \\"
echo "  -H \"Content-Type: application/json\" \\"
echo "  -d '{\"workflowRunId\":\"${WORKFLOW_RUN_ID}\",\"taskId\":\"${FIRST_TASK}\",\"success\":true,\"lastError\":null}'"
echo ""

echo -e "${BLUE}Example 4: Complete task with FAILURE (JSON body):${NC}"
echo "curl -X POST \"${BASE_URL}/api/workflows/task/callback\" \\"
echo "  -H \"Content-Type: application/json\" \\"
echo "  -d '{\"workflowRunId\":\"${WORKFLOW_RUN_ID}\",\"taskId\":\"${FIRST_TASK}\",\"success\":false,\"lastError\":\"Task failed\"}'"
echo ""

echo "=========================================="
echo "  TESTING COMPLETE"
echo "=========================================="
echo ""
echo "Workflow Run ID: ${WORKFLOW_RUN_ID}"
echo ""
echo "Next steps:"
echo "1. Monitor the workflow: curl ${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}"
echo "2. Check task details: curl ${BASE_URL}/api/workflows/${WORKFLOW_RUN_ID}/tasks"
echo "3. Complete tasks manually (if worker not running) using the examples above"
echo "4. View database: ./visualize-database.sh"
echo ""
