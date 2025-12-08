name: web_application_deployment
description: Deploy web application with tests and rollback
tasks:
  - id: checkout_code
    name: Git Checkout
    command: git clone https://github.com/myapp/webapp.git /tmp/webapp && cd /tmp/webapp && git checkout main
    max_retries: 2

  - id: install_dependencies
    name: Install Dependencies
    depends_on:
      - checkout_code
    command: cd /tmp/webapp && npm install
    max_retries: 3

  - id: run_tests
    name: Run Unit Tests
    depends_on:
      - install_dependencies
    command: cd /tmp/webapp && npm test
    max_retries: 1

  - id: build_app
    name: Build Production Bundle
    depends_on:
      - run_tests
    command: cd /tmp/webapp && npm run build
    max_retries: 2

  - id: deploy_staging
    name: Deploy to Staging
    depends_on:
      - build_app
    command: scp -r /tmp/webapp/dist/* user@staging-server:/var/www/html/
    max_retries: 2

  - id: smoke_test
    name: Smoke Test Staging
    depends_on:
      - deploy_staging
    command: curl -f https://staging.myapp.com/health || exit 1
    max_retries: 3

  - id: deploy_production
    name: Deploy to Production
    depends_on:
      - smoke_test
    command: scp -r /tmp/webapp/dist/* user@prod-server:/var/www/html/
    max_retries: 1

  - id: notify_team
    name: Send Slack Notification
    depends_on:
      - deploy_production
    command: curl -X POST -H 'Content-type:application/json' --data '{"text":"🚀 Deployment successful!"}' https://hooks.slack.com/services/YOUR_WEBHOOK

