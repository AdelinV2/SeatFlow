# TASK-009: Base CI Workflow (GitHub Actions PR Check)

## 1. Task Metadata
- **Target Module:** `.github/workflows/`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/08-observability-and-deployment.md`, `AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Create the baseline GitHub Actions automated Continuous Integration (CI) pipeline `.github/workflows/ci-pr-check.yml`. This workflow validates all Pull Requests targeting `develop` or `main` branches by running clean Maven builds, checking code formatting, compiling Java 21 sources, and executing all unit/slice test suites.

### Critical Invariants to Enforce:
- [ ] Workflow triggers on `pull_request` against `develop` and `main`, as well as manual `workflow_dispatch`.
- [ ] Uses official `actions/setup-java@v4` with Java `21` (distribution: `temurin`).
- [ ] Enables Maven dependency caching (`cache: 'maven'`) for fast PR feedback loops.
- [ ] Executes `mvn clean verify` across the backend multi-module project.
- [ ] Strict failure policy: any compilation warning, test failure, or build error immediately fails the PR check.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `.github/workflows/ci-pr-check.yml`

---

## 4. Technical Specifications & Contracts

### 4.1 GitHub Actions Workflow Specification (`.github/workflows/ci-pr-check.yml`)
```yaml
name: CI - PR Verification

on:
  pull_request:
    branches:
      - develop
      - main
    paths:
      - 'backend/**'
      - '.github/workflows/ci-pr-check.yml'
  workflow_dispatch:

concurrency:
  group: ci-pr-${{ github.head_ref || github.run_id }}
  cancel-in-progress: true

jobs:
  backend-build:
    name: Backend CI (Java 21 / Maven)
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Set up JDK 21 (Eclipse Temurin)
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Validate Maven Wrapper / Root POM
        run: |
          mvn -f backend/pom.xml validate

      - name: Build and Test Backend Modules
        run: |
          mvn -f backend/pom.xml clean verify -B --no-transfer-progress

      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: surefire-reports
          path: 'backend/**/target/surefire-reports/'
          if-no-files-found: ignore
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `.github/workflows/ci-pr-check.yml`.
2. **Step 2:** Ensure paths filter covers `backend/**` and workflow definition files.
3. **Step 3:** Validate YAML syntax and action versions.
4. **Step 4:** Ensure test report artifact uploading is preserved on failure.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
# Check YAML syntax validity
git status
```
- [ ] `.github/workflows/ci-pr-check.yml` created with valid GitHub Actions syntax.
- [ ] Triggers properly configure pull requests to `develop` and `main`.
- [ ] Task file is moved to `.ai/tasks/completed/`.
