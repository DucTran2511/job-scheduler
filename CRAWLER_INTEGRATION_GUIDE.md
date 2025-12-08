# Job Market Crawler Integration Guide

## 🎯 Overview

This guide shows how to use the Job Scheduler to orchestrate multi-threaded web crawling and AI processing for job market data from LinkedIn, TopCV, and ITViec.

---

## 🏗️ Architecture

### **Problem Solved:**
- **Multi-site crawling**: Parallel crawling from 3 job sites
- **AI processing**: Extract structured data from HTML using LLMs
- **Scalability**: Distribute work across multiple worker machines
- **Reliability**: Automatic retries, error handling, progress tracking
- **Scheduling**: Run daily/hourly automatically

### **Workflow Pipeline:**

```
1. Crawl (Parallel)
   ├─ LinkedIn  ─┐
   ├─ TopCV     ─┤
   └─ ITViec    ─┘
          ↓
2. Merge & Deduplicate
          ↓
3. Split into Batches
          ↓
4. AI Processing (Parallel)
   ├─ Batch 1  ─┐
   ├─ Batch 2  ─┤
   └─ Batch 3  ─┘
          ↓
5. Store to Database
          ↓
6. Generate Reports
          ↓
7. Notify Team
```

---

## 🚀 Quick Start

### **Step 1: Setup Crawler Scripts**

Create a crawler directory:
```bash
mkdir -p /opt/crawlers
cd /opt/crawlers
```

Install dependencies:
```bash
pip3 install selenium beautifulsoup4 requests openai psycopg2-binary
```

### **Step 2: Submit Workflow**

```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @api-service/src/main/resources/workflow-samples/job_market_crawler.yaml
```

Response:
```json
{
  "workflowRunId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### **Step 3: Monitor Progress**

```bash
# Check workflow status
curl http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000

# Watch logs
docker logs -f job_worker_1
```

---

## 📝 Crawler Script Examples

### **1. LinkedIn Crawler** (`linkedin_crawler.py`)

```python
#!/usr/bin/env python3
import argparse
import json
import time
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.chrome.options import Options

def crawl_linkedin(max_pages=10):
    chrome_options = Options()
    chrome_options.add_argument("--headless")
    chrome_options.add_argument("--no-sandbox")
    chrome_options.add_argument("--disable-dev-shm-usage")
    
    driver = webdriver.Chrome(options=chrome_options)
    jobs = []
    
    try:
        for page in range(max_pages):
            url = f"https://www.linkedin.com/jobs/search/?keywords=software%20engineer&start={page*25}"
            driver.get(url)
            time.sleep(2)
            
            job_cards = driver.find_elements(By.CLASS_NAME, "job-card-container")
            
            for card in job_cards:
                try:
                    job = {
                        "source": "linkedin",
                        "title": card.find_element(By.CLASS_NAME, "job-card-list__title").text,
                        "company": card.find_element(By.CLASS_NAME, "job-card-container__company-name").text,
                        "location": card.find_element(By.CLASS_NAME, "job-card-container__metadata-item").text,
                        "url": card.find_element(By.TAG_NAME, "a").get_attribute("href"),
                        "html": card.get_attribute("outerHTML"),
                        "crawled_at": time.strftime("%Y-%m-%d %H:%M:%S")
                    }
                    jobs.append(job)
                except Exception as e:
                    print(f"Error parsing job card: {e}")
                    continue
            
            print(f"✅ Crawled page {page+1}/{max_pages}: {len(job_cards)} jobs")
    
    finally:
        driver.quit()
    
    return jobs

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, help="Output JSON file")
    parser.add_argument("--max-pages", type=int, default=10, help="Max pages to crawl")
    args = parser.parse_args()
    
    jobs = crawl_linkedin(args.max_pages)
    
    with open(args.output, 'w', encoding='utf-8') as f:
        json.dump(jobs, f, ensure_ascii=False, indent=2)
    
    print(f"🎉 Crawled {len(jobs)} jobs from LinkedIn → {args.output}")
```

### **2. AI Extractor** (`ai_extractor.py`)

```python
#!/usr/bin/env python3
import argparse
import json
import os
from openai import OpenAI

client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

EXTRACTION_PROMPT = """
Extract structured information from this job posting HTML:

{html}

Return JSON with:
{
  "job_title": "exact title",
  "company_name": "company",
  "location": "city, country",
  "salary_range": "min-max or null",
  "employment_type": "Full-time/Part-time/Contract",
  "experience_level": "Junior/Mid/Senior",
  "required_skills": ["skill1", "skill2"],
  "job_description": "brief summary",
  "benefits": ["benefit1", "benefit2"]
}
"""

def extract_with_ai(job_data, model="gpt-4"):
    """Extract structured data from job HTML using OpenAI"""
    try:
        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": "You are a job data extraction expert. Return only valid JSON."},
                {"role": "user", "content": EXTRACTION_PROMPT.format(html=job_data.get("html", ""))}
            ],
            temperature=0.1,
            max_tokens=1000
        )
        
        extracted = json.loads(response.choices[0].message.content)
        
        # Merge with original data
        result = {
            **job_data,
            "extracted": extracted,
            "ai_processed": True
        }
        
        return result
    
    except Exception as e:
        print(f"❌ AI extraction failed: {e}")
        return {**job_data, "ai_processed": False, "error": str(e)}

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Input JSON batch file")
    parser.add_argument("--output", required=True, help="Output JSON file")
    parser.add_argument("--model", default="gpt-4", help="OpenAI model")
    args = parser.parse_args()
    
    with open(args.input, 'r') as f:
        jobs = json.load(f)
    
    processed = []
    for idx, job in enumerate(jobs):
        print(f"Processing {idx+1}/{len(jobs)}: {job.get('title', 'Unknown')}")
        result = extract_with_ai(job, args.model)
        processed.append(result)
    
    with open(args.output, 'w', encoding='utf-8') as f:
        json.dump(processed, f, ensure_ascii=False, indent=2)
    
    success_count = sum(1 for j in processed if j.get("ai_processed"))
    print(f"🎉 Processed {success_count}/{len(jobs)} jobs successfully")
```

### **3. Database Importer** (`db_importer.py`)

```python
#!/usr/bin/env python3
import argparse
import json
import psycopg2
from psycopg2.extras import execute_values

def import_to_db(jobs_file, db_url, table_name):
    """Import processed jobs to PostgreSQL"""
    
    with open(jobs_file, 'r') as f:
        jobs = json.load(f)
    
    conn = psycopg2.connect(db_url)
    cur = conn.cursor()
    
    # Create table if not exists
    cur.execute(f"""
        CREATE TABLE IF NOT EXISTS {table_name} (
            id SERIAL PRIMARY KEY,
            source VARCHAR(50),
            title TEXT,
            company TEXT,
            location TEXT,
            url TEXT UNIQUE,
            salary_range TEXT,
            employment_type VARCHAR(50),
            experience_level VARCHAR(50),
            required_skills JSONB,
            job_description TEXT,
            benefits JSONB,
            html TEXT,
            crawled_at TIMESTAMP,
            created_at TIMESTAMP DEFAULT NOW()
        )
    """)
    
    # Prepare data for insertion
    values = []
    for job in jobs:
        extracted = job.get('extracted', {})
        values.append((
            job.get('source'),
            extracted.get('job_title', job.get('title')),
            extracted.get('company_name', job.get('company')),
            extracted.get('location', job.get('location')),
            job.get('url'),
            extracted.get('salary_range'),
            extracted.get('employment_type'),
            extracted.get('experience_level'),
            json.dumps(extracted.get('required_skills', [])),
            extracted.get('job_description'),
            json.dumps(extracted.get('benefits', [])),
            job.get('html'),
            job.get('crawled_at')
        ))
    
    # Bulk insert with conflict handling
    execute_values(
        cur,
        f"""
        INSERT INTO {table_name} 
        (source, title, company, location, url, salary_range, employment_type, 
         experience_level, required_skills, job_description, benefits, html, crawled_at)
        VALUES %s
        ON CONFLICT (url) DO UPDATE SET
            title = EXCLUDED.title,
            company = EXCLUDED.company,
            crawled_at = EXCLUDED.crawled_at
        """,
        values
    )
    
    conn.commit()
    inserted = cur.rowcount
    
    cur.close()
    conn.close()
    
    print(f"✅ Imported {inserted} jobs to database")
    return inserted

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--table", default="job_listings")
    args = parser.parse_args()
    
    import_to_db(args.input, args.db_url, args.table)
```

---

## 🔧 Configuration

### **Environment Variables**

```bash
# Worker service
export OPENAI_API_KEY="sk-your-key-here"
export DATABASE_URL="postgresql://jobuser:jobpass@localhost:5432/jobdb"
export SLACK_WEBHOOK_URL="https://hooks.slack.com/services/YOUR_WEBHOOK"
```

### **Crawler Worker Setup**

Update `docker-compose.yaml`:

```yaml
services:
  worker-crawler:
    build: ./worker-service
    environment:
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - DATABASE_URL=${DATABASE_URL}
      - SPRING_PROFILES_ACTIVE=prod
    volumes:
      - /opt/crawlers:/opt/crawlers
      - /tmp/jobs:/tmp/jobs
    depends_on:
      - postgres
      - redis
```

---

## 📊 Scheduling Workflows

### **Option 1: Manual Trigger**
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @workflow-samples/job_market_crawler.yaml
```

### **Option 2: Cron Job (Daily at 2 AM)**
```bash
crontab -e
```

Add:
```
0 2 * * * curl -X POST http://localhost:8080/api/workflows/start -H "Content-Type: text/plain" --data-binary @/path/to/job_market_crawler.yaml
```

### **Option 3: API Scheduler (Future Enhancement)**
```java
// Coming in Week 3-4 of your roadmap
@Scheduled(cron = "0 0 2 * * *")
public void scheduleDailyCrawl() {
    orchestrator.startWorkflow(workflowYaml);
}
```

---

## 🎯 Advantages Over Traditional Crawlers

| Feature | Traditional Crawler | With Job Scheduler |
|---------|-------------------|-------------------|
| **Retry Logic** | Manual implementation | Automatic (max_retries) |
| **Parallel Execution** | Threading/multiprocessing | Built-in DAG parallelism |
| **State Tracking** | Custom database | Built-in workflow tracking |
| **Error Recovery** | Manual restart | Resume from failed task |
| **Monitoring** | Custom logging | Centralized dashboard |
| **Scalability** | Single machine | Distributed workers |
| **Scheduling** | Cron + custom code | Built-in scheduler |

---

## 📈 Performance Optimization

### **1. Increase Parallelism**

Split AI processing into more batches:
```yaml
# Instead of 3 batches, use 10
- id: ai_process_batch_1
  ...
- id: ai_process_batch_10
```

### **2. Scale Workers Horizontally**

```bash
docker-compose up --scale worker-crawler=5
```

### **3. Optimize Batch Sizes**

```python
# Split into smaller batches for faster processing
python3 split_batches.py --batch-size 20  # Instead of 50
```

### **4. Use Faster AI Models**

```yaml
command: python3 ai_extractor.py --model gpt-3.5-turbo  # Faster & cheaper
```

---

## 🐛 Troubleshooting

### **Issue: Crawler times out**
**Solution**: Increase `timeout_seconds` in workflow:
```yaml
timeout_seconds: 1800  # 30 minutes
```

### **Issue: Too many API rate limits**
**Solution**: Add delays between requests:
```python
time.sleep(random.uniform(2, 5))  # Random delay
```

### **Issue: Worker runs out of memory**
**Solution**: Process smaller batches or increase worker memory:
```yaml
deploy:
  resources:
    limits:
      memory: 4G
```

---

## 🚀 Next Steps

1. **Implement the crawler scripts** using examples above
2. **Test locally** with small datasets
3. **Deploy workers** with Docker Compose
4. **Set up scheduling** for daily runs
5. **Add monitoring** (coming in Week 15-16)
6. **Scale horizontally** as data volume grows

---

## 💡 Advanced Use Cases

### **1. Incremental Crawling**

Only crawl new jobs since last run:
```yaml
command: python3 linkedin_crawler.py --since-date $(date -d '1 day ago' +%Y-%m-%d)
```

### **2. Multi-Region Crawling**

Crawl different regions in parallel:
```yaml
- id: crawl_linkedin_us
  command: python3 linkedin_crawler.py --region US
- id: crawl_linkedin_eu
  command: python3 linkedin_crawler.py --region EU
```

### **3. Real-time Alerts**

Notify when specific jobs match criteria:
```yaml
- id: match_senior_roles
  command: python3 match_criteria.py --input /tmp/jobs/final.json --level Senior --notify
```

---

Your job scheduler transforms a complex multi-threaded crawling problem into a **simple, maintainable, scalable workflow**! 🎉

