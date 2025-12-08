# Job Scheduler - Real-World Use Cases

## 🎯 Overview

This document demonstrates how the Job Scheduler solves real-world business problems beyond basic task execution.

---

## 1. 🕷️ Web Crawling + AI Data Extraction

### **Business Problem:**
Need to aggregate job postings from multiple job sites (LinkedIn, TopCV, ITViec), extract structured data using AI, and store in database for analysis.

### **Traditional Approach Pain Points:**
- Hard to coordinate multi-site crawling
- Manual retry logic when crawls fail
- Difficult to parallelize AI processing
- Complex state management
- No visibility into pipeline progress

### **Job Scheduler Solution:**

**Workflow:** `job_market_crawler.yaml`

```yaml
# Parallel crawling (3 sites simultaneously)
crawl_linkedin → \
crawl_topcv    → → merge → split_batches → AI_batch_1 → \
crawl_itviec   → /                       → AI_batch_2 → → store_db → report
                                         → AI_batch_3 → /
```

**Benefits:**
- ✅ **Automatic parallelization**: 3 sites crawled simultaneously
- ✅ **Built-in retries**: Each task retries 3x on failure
- ✅ **State tracking**: Know exactly which jobs are processed
- ✅ **Scalable**: Add more workers to process faster
- ✅ **Schedulable**: Run daily at 2 AM automatically

**See:** `CRAWLER_INTEGRATION_GUIDE.md` for complete implementation

---

## 2. 📊 Daily ETL Pipeline

### **Business Problem:**
Extract data from multiple sources (APIs, databases, S3), transform it, and load into data warehouse for analytics.

### **Workflow:** `daily_etl.yaml`

```yaml
name: daily_sales_etl
tasks:
  - id: extract_from_salesforce
    command: python3 extract_salesforce.py --date yesterday
  
  - id: extract_from_postgresql
    command: pg_dump -t orders | gzip > /tmp/orders.sql.gz
  
  - id: extract_from_s3
    command: aws s3 sync s3://raw-data/$(date +%Y/%m/%d) /tmp/s3-data/
  
  - id: transform_data
    depends_on: [extract_from_salesforce, extract_from_postgresql, extract_from_s3]
    command: python3 transform.py --input-dir /tmp --output /tmp/transformed.parquet
  
  - id: load_to_warehouse
    depends_on: [transform_data]
    command: python3 load_snowflake.py --file /tmp/transformed.parquet
  
  - id: refresh_analytics_views
    depends_on: [load_to_warehouse]
    command: psql -c "REFRESH MATERIALIZED VIEW daily_sales_summary"
```

**Benefits:**
- Parallel extraction from multiple sources
- Automatic retry on network failures
- Clear dependency chain
- Audit trail of all executions

---

## 3. 🎬 Video Processing Pipeline

### **Business Problem:**
User uploads video → transcode to multiple formats (1080p, 720p, 480p) → generate thumbnails → upload to CDN → update database

### **Workflow:** `video_transcoding.yaml`

```yaml
download_video → extract_thumbnail → \
                                     → transcode_1080p → upload_1080p → \
                                     → transcode_720p  → upload_720p  → → update_db
                                     → transcode_480p  → upload_480p  → /
```

**Benefits:**
- Parallel transcoding (saves hours for large videos)
- Retry failed uploads automatically
- Track processing status in real-time
- Scale workers for peak upload times

---

## 4. 🔒 Security Scanning Pipeline

### **Business Problem:**
Daily security scans across infrastructure: code analysis, dependency checks, container scanning, penetration testing

### **Workflow:** `security_scan.yaml`

```yaml
name: daily_security_audit
tasks:
  - id: scan_code_sonarqube
    command: sonar-scanner -Dsonar.projectKey=myapp
  
  - id: scan_dependencies_snyk
    command: snyk test --all-projects --json > /tmp/snyk-report.json
  
  - id: scan_containers_trivy
    command: trivy image --severity HIGH,CRITICAL myapp:latest
  
  - id: generate_security_report
    depends_on: [scan_code_sonarqube, scan_dependencies_snyk, scan_containers_trivy]
    command: python3 generate_report.py --output /tmp/security-report.pdf
  
  - id: upload_to_compliance_system
    depends_on: [generate_security_report]
    command: curl -X POST https://compliance.company.com/upload -F file=@/tmp/security-report.pdf
```

---

## 5. 🚀 CI/CD Deployment Pipeline

### **Business Problem:**
Build → Test → Deploy to staging → Run smoke tests → Deploy to production → Notify team

### **Workflow:** `web_deployment.yaml`

```yaml
name: production_deployment
tasks:
  - id: checkout_code
    command: git clone https://github.com/myapp/webapp.git /tmp/webapp
  
  - id: install_dependencies
    depends_on: [checkout_code]
    command: cd /tmp/webapp && npm install
  
  - id: run_tests
    depends_on: [install_dependencies]
    command: cd /tmp/webapp && npm test
  
  - id: build_app
    depends_on: [run_tests]
    command: cd /tmp/webapp && npm run build
  
  - id: deploy_staging
    depends_on: [build_app]
    command: scp -r /tmp/webapp/dist/* user@staging:/var/www/html/
  
  - id: smoke_test_staging
    depends_on: [deploy_staging]
    command: curl -f https://staging.myapp.com/health || exit 1
  
  - id: deploy_production
    depends_on: [smoke_test_staging]
    command: scp -r /tmp/webapp/dist/* user@prod:/var/www/html/
  
  - id: notify_slack
    depends_on: [deploy_production]
    command: curl -X POST $SLACK_WEBHOOK -d '{"text":"✅ Production deployed!"}'
```

---

## 6. 🤖 Machine Learning Pipeline

### **Business Problem:**
Train ML model → evaluate → deploy if better than current → update API

### **Workflow:** `ml_training.yaml`

```yaml
name: ml_model_retrain
tasks:
  - id: fetch_training_data
    command: python3 fetch_data.py --days 30 --output /tmp/train.csv
  
  - id: preprocess_data
    depends_on: [fetch_training_data]
    command: python3 preprocess.py --input /tmp/train.csv --output /tmp/train_processed.csv
  
  - id: train_model
    depends_on: [preprocess_data]
    command: python3 train.py --data /tmp/train_processed.csv --output /tmp/model.pkl
  
  - id: evaluate_model
    depends_on: [train_model]
    command: python3 evaluate.py --model /tmp/model.pkl --test-data /tmp/test.csv
  
  - id: deploy_if_better
    depends_on: [evaluate_model]
    command: python3 deploy_model.py --model /tmp/model.pkl --threshold 0.95
```

---

## 7. 📧 Email Campaign Workflow

### **Business Problem:**
Segment users → generate personalized content → send emails → track opens/clicks

### **Workflow:** `email_campaign.yaml`

```yaml
name: weekly_newsletter
tasks:
  - id: segment_users
    command: python3 segment.py --output /tmp/segments.json
  
  - id: generate_content_segment_1
    depends_on: [segment_users]
    command: python3 generate_emails.py --segment active_users --output /tmp/emails_1.json
  
  - id: generate_content_segment_2
    depends_on: [segment_users]
    command: python3 generate_emails.py --segment inactive_users --output /tmp/emails_2.json
  
  - id: send_emails_segment_1
    depends_on: [generate_content_segment_1]
    command: python3 send_emails.py --input /tmp/emails_1.json
  
  - id: send_emails_segment_2
    depends_on: [generate_content_segment_2]
    command: python3 send_emails.py --input /tmp/emails_2.json
```

---

## 8. 💾 Database Backup & Disaster Recovery

### **Workflow:** `daily_backup.yaml`

```yaml
name: database_backup_workflow
tasks:
  - id: backup_postgres
    command: pg_dump -U jobuser jobdb > /tmp/backup_$(date +%Y%m%d).sql
  
  - id: compress_backup
    depends_on: [backup_postgres]
    command: gzip /tmp/backup_$(date +%Y%m%d).sql
  
  - id: upload_to_s3
    depends_on: [compress_backup]
    command: aws s3 cp /tmp/backup_$(date +%Y%m%d).sql.gz s3://backups/postgres/
  
  - id: verify_backup
    depends_on: [upload_to_s3]
    command: aws s3 ls s3://backups/postgres/backup_$(date +%Y%m%d).sql.gz
  
  - id: cleanup_old_backups
    depends_on: [verify_backup]
    command: find /tmp -name "backup_*.sql.gz" -mtime +7 -delete
```

---

## 9. 🌐 Website Monitoring & Alerting

### **Workflow:** `website_monitoring.yaml`

```yaml
name: website_health_check
tasks:
  - id: check_homepage
    command: curl -f -o /dev/null -s -w "%{http_code}" https://myapp.com
  
  - id: check_api_health
    command: curl -f https://api.myapp.com/health
  
  - id: check_database_connection
    command: psql -c "SELECT 1" > /dev/null
  
  - id: check_redis_connection
    command: redis-cli ping
  
  - id: send_alert_if_down
    depends_on: [check_homepage, check_api_health, check_database_connection, check_redis_connection]
    command: python3 alert.py --metrics /tmp/health_check.json
```

---

## 10. 📦 Inventory Sync Workflow

### **Business Problem:**
E-commerce company needs to sync inventory across multiple sales channels (Shopify, Amazon, eBay)

### **Workflow:** `inventory_sync.yaml`

```yaml
name: inventory_sync_workflow
tasks:
  - id: get_current_inventory
    command: python3 get_inventory.py --output /tmp/inventory.json
  
  - id: sync_to_shopify
    depends_on: [get_current_inventory]
    command: python3 sync_shopify.py --input /tmp/inventory.json
  
  - id: sync_to_amazon
    depends_on: [get_current_inventory]
    command: python3 sync_amazon.py --input /tmp/inventory.json
  
  - id: sync_to_ebay
    depends_on: [get_current_inventory]
    command: python3 sync_ebay.py --input /tmp/inventory.json
  
  - id: log_sync_results
    depends_on: [sync_to_shopify, sync_to_amazon, sync_to_ebay]
    command: python3 log_results.py --timestamp $(date +%s)
```

---

## 🎯 Common Patterns

### **Pattern 1: Fan-Out (Parallel Processing)**
```
     task_1 → \
     task_2 → → merge_task
     task_3 → /
```
**Use cases:** Multi-site crawling, parallel transcoding, multi-region deployments

### **Pattern 2: Sequential Pipeline**
```
task_1 → task_2 → task_3 → task_4
```
**Use cases:** ETL, CI/CD, data processing

### **Pattern 3: Conditional Branching (Future)**
```
           ┌→ success_task
task → decision
           └→ failure_task
```
**Use cases:** A/B testing, canary deployments, smart routing

---

## 📊 ROI Calculator

### **Example: Web Crawling Use Case**

**Before Job Scheduler:**
- Manual coordination: 2 hours/week
- Debugging failures: 3 hours/week
- No parallelization: 45 min per run
- Developer time: $100/hour

**Annual Cost:** (5 hours × $100) × 52 weeks = **$26,000/year**

**After Job Scheduler:**
- Automated: 0 hours/week
- Self-healing retries: 0.5 hours/week
- Parallel execution: 10 min per run
- Maintenance: 1 hour/week

**Annual Cost:** (1.5 hours × $100) × 52 weeks = **$7,800/year**

**Savings:** **$18,200/year** + 75% faster execution

---

## 🚀 Getting Started

1. **Choose your use case** from above
2. **Copy the workflow YAML** to your project
3. **Customize the commands** for your environment
4. **Submit the workflow:**
   ```bash
   curl -X POST http://localhost:8080/api/workflows/start \
     -H "Content-Type: text/plain" \
     --data-binary @workflow.yaml
   ```
5. **Monitor execution** via logs or dashboard

---

Your job scheduler is a **Swiss Army knife** for automation! 🛠️
name: job_market_daily_crawler
description: Daily job market data crawling from LinkedIn, TopCV, ITViec with AI processing
tasks:
  # ========================================
  # PHASE 1: Parallel Crawling
  # ========================================
  - id: crawl_linkedin
    name: Crawl LinkedIn Jobs
    command: python3 /opt/crawlers/linkedin_crawler.py --output /tmp/jobs/linkedin.json --max-pages 10
    max_retries: 3
    timeout_seconds: 600

  - id: crawl_topcv
    name: Crawl TopCV Jobs
    command: python3 /opt/crawlers/topcv_crawler.py --output /tmp/jobs/topcv.json --max-pages 10
    max_retries: 3
    timeout_seconds: 600

  - id: crawl_itviec
    name: Crawl ITViec Jobs
    command: python3 /opt/crawlers/itviec_crawler.py --output /tmp/jobs/itviec.json --max-pages 10
    max_retries: 3
    timeout_seconds: 600

  # ========================================
  # PHASE 2: Merge & Deduplicate
  # ========================================
  - id: merge_crawled_data
    name: Merge All Crawled Data
    depends_on:
      - crawl_linkedin
      - crawl_topcv
      - crawl_itviec
    command: python3 /opt/crawlers/merge_jobs.py --input-dir /tmp/jobs --output /tmp/jobs/merged.json --deduplicate
    max_retries: 2
    timeout_seconds: 300

  # ========================================
  # PHASE 3: AI Processing (Parallel Batches)
  # ========================================
  - id: split_for_ai_processing
    name: Split Jobs into Batches for AI
    depends_on:
      - merge_crawled_data
    command: python3 /opt/crawlers/split_batches.py --input /tmp/jobs/merged.json --output-dir /tmp/jobs/batches --batch-size 50
    max_retries: 2
    timeout_seconds: 120

  - id: ai_process_batch_1
    name: AI Extract - Batch 1
    depends_on:
      - split_for_ai_processing
    command: python3 /opt/crawlers/ai_extractor.py --input /tmp/jobs/batches/batch_1.json --output /tmp/jobs/processed/batch_1.json --model gpt-4
    max_retries: 3
    timeout_seconds: 1800

  - id: ai_process_batch_2
    name: AI Extract - Batch 2
    depends_on:
      - split_for_ai_processing
    command: python3 /opt/crawlers/ai_extractor.py --input /tmp/jobs/batches/batch_2.json --output /tmp/jobs/processed/batch_2.json --model gpt-4
    max_retries: 3
    timeout_seconds: 1800

  - id: ai_process_batch_3
    name: AI Extract - Batch 3
    depends_on:
      - split_for_ai_processing
    command: python3 /opt/crawlers/ai_extractor.py --input /tmp/jobs/batches/batch_3.json --output /tmp/jobs/processed/batch_3.json --model gpt-4
    max_retries: 3
    timeout_seconds: 1800

  # ========================================
  # PHASE 4: Data Storage
  # ========================================
  - id: merge_ai_results
    name: Merge AI Processed Results
    depends_on:
      - ai_process_batch_1
      - ai_process_batch_2
      - ai_process_batch_3
    command: python3 /opt/crawlers/merge_results.py --input-dir /tmp/jobs/processed --output /tmp/jobs/final.json
    max_retries: 2
    timeout_seconds: 300

  - id: store_to_database
    name: Store Jobs to PostgreSQL
    depends_on:
      - merge_ai_results
    command: python3 /opt/crawlers/db_importer.py --input /tmp/jobs/final.json --db-url postgresql://jobuser:jobpass@localhost:5432/jobdb --table job_listings
    max_retries: 3
    timeout_seconds: 600

  # ========================================
  # PHASE 5: Analytics & Reporting
  # ========================================
  - id: generate_statistics
    name: Generate Daily Statistics
    depends_on:
      - store_to_database
    command: python3 /opt/crawlers/generate_stats.py --date $(date +%Y-%m-%d) --output /tmp/jobs/stats.json
    max_retries: 2
    timeout_seconds: 180

  - id: send_slack_notification
    name: Send Summary to Slack
    depends_on:
      - generate_statistics
    command: |
      curl -X POST https://hooks.slack.com/services/YOUR_WEBHOOK \
        -H 'Content-Type: application/json' \
        -d "{\"text\":\"🎯 Daily Job Crawl Complete: $(cat /tmp/jobs/stats.json | jq -r '.total_jobs') jobs processed\"}"
    max_retries: 3
    timeout_seconds: 30

  # ========================================
  # PHASE 6: Cleanup
  # ========================================
  - id: cleanup_temp_files
    name: Clean Up Temporary Files
    depends_on:
      - send_slack_notification
    command: rm -rf /tmp/jobs/*
    max_retries: 1
    timeout_seconds: 60

