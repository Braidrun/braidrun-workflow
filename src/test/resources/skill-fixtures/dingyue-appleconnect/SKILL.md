---
translations:
  zh:
    name: "DingYue App Store Connect"
    description: >-
      用于检查或调用 Apple App Store Connect API、认证凭据、管理 App 元数据、订阅、价格、截图、版本、内购，以及 App Store Connect 相关发布流程。
  en:
    name: "DingYue App Store Connect"
    description: >-
      Use when working with Apple App Store Connect APIs, credentials, app metadata, subscriptions, pricing, screenshots, versions, in-app purchases, and App Store Connect release workflows.
  zhHant:
    name: "DingYue App Store Connect"
    description: >-
      在處理 Apple App Store Connect API、認證資訊、App 中繼資料、訂閱、定價、螢幕截圖、版本、App 內購買項目，以及 App Store Connect 發布工作流程時使用。
  ja:
    name: "DingYue App Store Connect"
    description: >-
      Apple App Store Connect API、認証情報、App メタデータ、サブスクリプション、価格、スクリーンショット、バージョン、アプリ内課金、App Store Connect のリリースワークフローを扱う場合に使用します。
  ko:
    name: "DingYue App Store Connect"
    description: >-
      Apple App Store Connect API, 자격 증명, 앱 메타데이터, 구독, 가격, 스크린샷, 버전, 앱 내 구입 및 App Store Connect 출시 워크플로를 다룰 때 사용합니다.
  es:
    name: "DingYue App Store Connect"
    description: >-
      Úsala al trabajar con las API de Apple App Store Connect, credenciales, metadatos de aplicaciones, suscripciones, precios, capturas de pantalla, versiones, compras dentro de la aplicación y flujos de publicación de App Store Connect.
  fr:
    name: "DingYue App Store Connect"
    description: >-
      À utiliser pour travailler avec les API Apple App Store Connect, les identifiants, les métadonnées d'app, les abonnements, la tarification, les captures d'écran, les versions, les achats intégrés et les workflows de publication App Store Connect.
  de:
    name: "DingYue App Store Connect"
    description: >-
      Verwenden bei der Arbeit mit Apple App Store Connect-APIs, Anmeldedaten, App-Metadaten, Abonnements, Preisen, Screenshots, Versionen, In-App-Käufen und App Store Connect-Veröffentlichungsabläufen.
  vi:
    name: "DingYue App Store Connect"
    description: >-
      Sử dụng khi làm việc với API Apple App Store Connect, thông tin xác thực, siêu dữ liệu ứng dụng, gói đăng ký, giá, ảnh chụp màn hình, phiên bản, giao dịch mua trong ứng dụng và quy trình phát hành trên App Store Connect.
  ar:
    name: "DingYue App Store Connect"
    description: >-
      يُستخدم عند العمل مع واجهات Apple App Store Connect API وبيانات الاعتماد والبيانات الوصفية للتطبيق والاشتراكات والأسعار ولقطات الشاشة والإصدارات وعمليات الشراء داخل التطبيق ومسارات إصدار App Store Connect.
  pt:
    name: "DingYue App Store Connect"
    description: >-
      Utilize ao trabalhar com as APIs do Apple App Store Connect, credenciais, metadados de aplicações, subscrições, preços, capturas de ecrã, versões, compras integradas e fluxos de lançamento do App Store Connect.
name: dingyue-appleconnect
description: >
  Apple App Store Connect API 全覆盖工具。支持 API v4.3 全部 192 个资源端点，
  涵盖 App 管理、版本发布、构建、证书与描述文件、TestFlight 测试、内购与订阅、
  Game Center、Xcode Cloud CI/CD、App Clips、评论、截图、分析报告等。
  使用 ES256 JWT (.p8 密钥) 认证。内置 32 个高级工作流命令，可一键完成版本提交、
  TestFlight 部署、IAP 创建等常见任务，同时支持 raw 命令直接访问任意 API 路径。
---

# Apple App Store Connect Skill

Python CLI for Apple App Store Connect API v4.3. 100% API coverage: 192 resource endpoints, 2888 pydantic models. Plus 18 high-level workflow scripts for end-to-end task automation.

## Quick Start

```bash
python scripts/cli.py config init
python scripts/cli.py config set --issuer-id YOUR_ID --key-id YOUR_KEY --private-key-path /path/to/AuthKey.p8
python scripts/cli.py auth test
```

Dependencies: `pip install PyJWT cryptography pydantic urllib3 python-dateutil`

## JSON:API Request Body Format

All create/update commands use `--json-body` with Apple's JSON:API format:

```json
{
  "data": {
    "type": "RESOURCE_TYPE",
    "attributes": { ... },
    "relationships": {
      "relatedResource": {
        "data": { "type": "otherType", "id": "OTHER_ID" }
      }
    }
  }
}
```

Use `--body-file path/to/request.json` as an alternative to inline JSON.

## Reference Documentation

**For low-level API commands:** read the relevant reference file before constructing create/update requests.

**For common tasks:** use `workflow` commands instead — they handle all JSON:API details internally.

## High-Level Workflow Commands (Recommended)

These scripts combine multiple API calls into single, simple commands. **Use these whenever possible instead of raw API calls.**

### App Submission Full Flow

The following 6 commands complete the entire app review submission lifecycle:

```bash
# 1. Create version with localized metadata
python scripts/cli.py workflow prepare-new-version \
    --app-id APP_ID --version 2.0.0 --platform IOS \
    --locales-json '{"en-US": {"description": "...", "keywords": "...", "whatsNew": "Bug fixes"}}'

# 2. Assign latest valid build to the version
python scripts/cli.py workflow set-build-for-version --app-id APP_ID
# (auto-detects PREPARE_FOR_SUBMISSION version and latest VALID build)

# 3. Set review contact info and demo account
python scripts/cli.py workflow configure-review-info \
    --version-id VERSION_ID \
    --contact-email review@example.com --contact-phone +1-555-1234 \
    --contact-first-name John --contact-last-name Doe \
    --demo-account testuser --demo-password testpass123 \
    --notes "Use demo account to test premium features"

# 4. Submit for review
python scripts/cli.py workflow submit-for-review --app-id APP_ID --platform IOS

# 5. Check review status
python scripts/cli.py workflow check-review-status --app-id APP_ID

# 6. Release (immediate or phased)
python scripts/cli.py workflow release-version --version-id VERSION_ID --release-type immediate
python scripts/cli.py workflow release-version --version-id VERSION_ID --release-type phased
python scripts/cli.py workflow release-version --version-id VERSION_ID --release-type phased --phased-action pause
```

### App Management

```bash
# List all apps with latest version status
python scripts/cli.py workflow list-apps-summary

# Full status report for one app
python scripts/cli.py workflow app-status-report --app-id APP_ID
```

### Certificates & Provisioning

```bash
# Check expiring certificates
python scripts/cli.py workflow renew-certificates --days 30

# Create provisioning profile (resolves bundle ID and devices automatically)
python scripts/cli.py workflow create-provisioning-profile \
    --name "Dev Profile" --type IOS_APP_DEVELOPMENT \
    --bundle-id-identifier com.example.app \
    --certificate-ids CERT1,CERT2 --all-devices
```

### TestFlight

```bash
# Set up TestFlight in one command (create group, add testers, assign build, set what-to-test)
python scripts/cli.py workflow setup-testflight \
    --app-id APP_ID --group-name "QA Team" \
    --tester-emails "a@example.com,b@example.com" \
    --what-to-test "Please test the new checkout flow"

# Batch invite testers with email invitations
python scripts/cli.py workflow invite-beta-testers \
    --app-id APP_ID --group-id GROUP_ID \
    --emails "c@example.com,d@example.com" --send-invitations
```

### Version Metadata Update

```bash
# Update whatsNew/description for existing version (auto-detects existing localizations)
python scripts/cli.py workflow update-version-metadata \
    --version-id VERSION_ID \
    --locales-json '{"en-US": {"whatsNew": "Bug fixes"}, "zh-Hans": {"whatsNew": "问题修复"}}'
```

### Export Compliance & Age Rating

```bash
# Set encryption compliance for build or app
python scripts/cli.py workflow set-export-compliance --build-id BUILD_ID --uses-encryption false
python scripts/cli.py workflow set-export-compliance --app-id APP_ID --uses-encryption false

# Set age rating
python scripts/cli.py workflow set-age-rating --age-rating-id AGE_ID \
    --violence-cartoon NONE --sexual-content NONE --profanity NONE \
    --gambling false --unrestricted-web-access false
```

### App Pricing & Availability

```bash
# Set/change app price
python scripts/cli.py workflow set-app-price --app-id APP_ID --base-territory USA --price-point-id PRICE_ID

# Set territory availability
python scripts/cli.py workflow manage-app-availability --app-id APP_ID --territories "USA,CHN,JPN,GBR,DEU"
```

### Remove from Sale / Developer Reject

```bash
python scripts/cli.py workflow remove-from-sale --app-id APP_ID --action remove
python scripts/cli.py workflow remove-from-sale --app-id APP_ID --action developer-reject
```

### Phased Release Monitoring

```bash
python scripts/cli.py workflow check-phased-release --app-id APP_ID
# Output: "Phased release: ACTIVE (day 3, 5% of users)"
```

### Builds

```bash
python scripts/cli.py workflow list-builds --limit 20
# Output: "15 builds (12 valid, 1 processing)"
```

### Device Management

```bash
python scripts/cli.py workflow manage-devices list --platform IOS
python scripts/cli.py workflow manage-devices register --name "iPhone 16" --udid XXXX --platform IOS
python scripts/cli.py workflow manage-devices bulk-register --devices-json '[{"name":"A","udid":"X","platform":"IOS"}]'
python scripts/cli.py workflow manage-devices disable --device-id DEV_ID
```

### Beta Build Management

```bash
python scripts/cli.py workflow manage-beta-builds list --group-id GROUP_ID
python scripts/cli.py workflow manage-beta-builds add --group-id GROUP_ID --build-id BUILD_ID
python scripts/cli.py workflow manage-beta-builds remove --group-id GROUP_ID --build-id BUILD_ID
```

### In-App Purchases & Subscriptions

```bash
# Create IAP with localizations
python scripts/cli.py workflow create-iap-product \
    --app-id APP_ID --product-id com.example.premium \
    --name "Premium Upgrade" --type NON_CONSUMABLE \
    --locales '{"en-US": {"name": "Premium", "description": "Unlock all features"}}'

# Create subscription with group and localizations
python scripts/cli.py workflow create-subscription \
    --app-id APP_ID --group-name "Premium Plans" \
    --product-id com.example.pro.monthly --name "Monthly Pro" \
    --period ONE_MONTH \
    --locales '{"en-US": {"name": "Monthly Pro", "description": "Full access"}}'

# Create offer/promo codes
python scripts/cli.py workflow create-offer-codes \
    --type subscription --subscription-id SUB_ID \
    --name SUMMER25 --duration ONE_MONTH --offer-mode FREE_TRIAL --eligibility NEW
```

### Customer Reviews

```bash
python scripts/cli.py workflow fetch-recent-reviews --app-id APP_ID --limit 20
python scripts/cli.py workflow reply-to-review --review-id REVIEW_ID --response "Thank you!"
```

### Reports

```bash
python scripts/cli.py workflow download-sales-report \
    --vendor-number 12345678 --report-date 2025-03-01 --frequency DAILY
python scripts/cli.py workflow download-finance-report \
    --vendor-number 12345678 --region-code US --report-date 2025-03
```

### CI/CD (Xcode Cloud)

```bash
# Trigger a build and wait for completion
python scripts/cli.py workflow trigger-xcode-cloud-build --workflow-id WF_ID --wait
```

### Custom Product Pages

```bash
python scripts/cli.py workflow manage-custom-product-pages list --app-id APP_ID
python scripts/cli.py workflow manage-custom-product-pages create --app-id APP_ID --name "Spring Campaign"
```

### Team Management

```bash
python scripts/cli.py workflow manage-users list
python scripts/cli.py workflow manage-users invite \
    --email dev@example.com --first-name Jane --last-name Doe --roles DEVELOPER
python scripts/cli.py workflow manage-users update --user-id USER_ID --roles ADMIN,DEVELOPER
python scripts/cli.py workflow manage-users remove --user-id USER_ID
```

### All 32 Workflow Commands

| Command | Description |
|---------|-------------|
| **Version Release Flow** | |
| `workflow prepare-new-version` | Create version + set localized metadata |
| `workflow update-version-metadata` | Update localized metadata for existing version |
| `workflow set-build-for-version` | Auto-detect and assign build to version |
| `workflow set-export-compliance` | Set encryption declaration for build/app |
| `workflow configure-review-info` | Set review contact info and demo account |
| `workflow submit-for-review` | Submit app for App Store review |
| `workflow check-review-status` | Check current review/release status |
| `workflow release-version` | Immediate or phased release |
| `workflow check-phased-release` | Check phased release progress (day/percent) |
| `workflow remove-from-sale` | Remove from sale or developer-reject |
| **App Management** | |
| `workflow list-apps-summary` | List all apps with summary |
| `workflow app-status-report` | Full status report for one app |
| `workflow set-app-price` | Set or change app price |
| `workflow set-age-rating` | Update age rating declaration |
| `workflow manage-app-availability` | Set territory availability |
| `workflow manage-custom-product-pages` | CRUD custom product pages |
| **Builds** | |
| `workflow list-builds` | List builds with processing status |
| **Provisioning** | |
| `workflow renew-certificates` | Check expiring certificates |
| `workflow create-provisioning-profile` | Create profile (auto-resolves bundle ID/devices) |
| `workflow manage-devices` | List/register/bulk-register/disable devices |
| **TestFlight** | |
| `workflow setup-testflight` | One-command TestFlight setup |
| `workflow invite-beta-testers` | Batch invite testers |
| `workflow manage-beta-builds` | Add/remove builds from beta groups |
| **Commerce** | |
| `workflow create-iap-product` | Create IAP with localizations |
| `workflow create-subscription` | Create subscription with group |
| `workflow create-offer-codes` | Create IAP/subscription offer codes |
| **Reviews** | |
| `workflow fetch-recent-reviews` | Fetch app reviews with ratings |
| `workflow reply-to-review` | Reply to customer review |
| **Reports** | |
| `workflow download-sales-report` | Download sales report |
| `workflow download-finance-report` | Download finance report |
| **CI/CD** | |
| `workflow trigger-xcode-cloud-build` | Trigger Xcode Cloud build (optional wait) |
| **Team** | |
| `workflow manage-users` | List/invite/update/remove team members |

## Reference Documentation (for low-level API access)

| Reference File | Content |
|---|---|
| `references/apps.md` | Apps, app info, localizations, categories, age ratings, availability, pricing, EULA, territories |
| `references/provisioning.md` | Certificates, devices, bundle IDs, capabilities, profiles (with all enum values) |
| `references/versions-and-releases.md` | Versions, localizations, phased releases, submissions, review details, experiments, release requests |
| `references/iap-and-subscriptions.md` | IAP, subscriptions, groups, pricing, offer codes, promotional offers, win-back offers, localizations |
| `references/beta-testing.md` | Builds, beta groups, beta testers, invitations, build localizations, crash logs |
| `references/reviews-and-metadata.md` | Customer reviews, responses, screenshots, previews, app events, encryption, custom product pages, sandbox testers |
| `references/ci-cd.md` | Xcode Cloud: products, workflows, build runs, actions, artifacts, test results, Xcode/macOS versions |
| `references/game-center.md` | Achievements, leaderboards, sets, groups, matchmaking, challenges, activities (with all sub-resources) |
| `references/scm-and-infrastructure.md` | SCM providers/repositories, alternative distribution, background assets, webhooks, marketplace |

## Common Flags

| Flag | Description |
|------|-------------|
| `--id X` | Resource ID |
| `--app-id X` | App ID (for child resources) |
| `--group-id X` | Subscription Group ID |
| `--limit N` | Result limit (default 50) |
| `--sort X` | Sort fields, comma-separated (prefix with `-` for descending) |
| `--brief` | Summarized output |
| `--json-body '{...}'` | JSON:API request body |
| `--body-file path` | Request body from file |
| `--filter-bundle-id X` | Filter by bundle ID |
| `--filter-name X` | Filter by name |
| `--filter-type X` | Filter by type |
| `--filter-platform X` | Filter by platform (IOS, MAC_OS) |

## All 195 Command Domains

### Core (3)
`config` `auth` `raw`

### Apps & Metadata (21)
`apps` `app-infos` `app-info-localizations` `app-categories` `age-ratings` `accessibility-declarations` `app-availabilities` `app-price-points` `app-price-schedules` `eula` `app-tags` `app-events` `app-event-localizations` `app-event-screenshots` `app-event-video-clips` `custom-product-pages` `custom-product-page-versions` `custom-product-page-localizations` `encryption-declarations` `encryption-declaration-documents` `routing-app-coverages`

### Provisioning (5)
`certificates` `devices` `bundle-ids` `bundle-id-capabilities` `profiles`

### Versions & Release (11)
`versions` `version-localizations` `phased-releases` `version-submissions` `release-requests` `version-experiments` `version-experiment-treatments` `version-experiment-treatment-localizations` `version-promotions` `review-details` `review-attachments`

### Builds & Beta Testing (18)
`builds` `build-beta-details` `build-beta-notifications` `build-bundles` `build-uploads` `build-upload-files` `pre-release-versions` `beta-testers` `beta-groups` `beta-tester-invitations` `beta-app-localizations` `beta-app-review-details` `beta-app-review-submissions` `beta-build-localizations` `beta-license-agreements` `beta-app-clip-invocations` `beta-app-clip-invocation-localizations` `beta-crash-logs`

### Beta Feedback & Recruitment (4)
`beta-feedback-crash-submissions` `beta-feedback-screenshot-submissions` `beta-recruitment-criteria` `beta-recruitment-criterion-options`

### Review (3)
`review` `review-submission-items` `review-responses`

### Customer Reviews (2)
`customer-reviews` `review-responses`

### In-App Purchases (12)
`iap` `iap-localizations` `iap-price-points` `iap-price-schedules` `iap-availabilities` `iap-offer-codes` `iap-offer-code-custom-codes` `iap-offer-code-one-time-use-codes` `iap-submissions` `iap-contents` `iap-images` `iap-review-screenshots`

### Subscriptions (17)
`subscriptions` `subscription-groups` `subscription-localizations` `subscription-prices` `subscription-price-points` `subscription-introductory-offers` `subscription-offer-codes` `subscription-offer-code-custom-codes` `subscription-offer-code-one-time-use-codes` `subscription-promotional-offers` `subscription-availabilities` `subscription-grace-periods` `subscription-group-localizations` `subscription-group-submissions` `subscription-images` `subscription-review-screenshots` `subscription-submissions`

### Win-Back & Promoted (2)
`win-back-offers` `promoted-purchases`

### Commerce & Reports (4)
`territories` `territory-availabilities` `finance-reports` `sales-reports`

### Screenshots & Previews (4)
`screenshot-sets` `screenshots` `preview-sets` `previews`

### CI/CD — Xcode Cloud (9)
`ci-products` `ci-workflows` `ci-build-runs` `ci-build-actions` `ci-artifacts` `ci-test-results` `ci-issues` `ci-xcode-versions` `ci-macos-versions`

### SCM — Source Control (4)
`scm-providers` `scm-repositories` `scm-git-references` `scm-pull-requests`

### Game Center (30)
`gc-details` `gc-achievements` `gc-achievement-localizations` `gc-achievement-images` `gc-achievement-releases` `gc-achievement-versions` `gc-leaderboards` `gc-leaderboard-localizations` `gc-leaderboard-images` `gc-leaderboard-releases` `gc-leaderboard-versions` `gc-leaderboard-entry-submissions` `gc-leaderboard-sets` `gc-leaderboard-set-localizations` `gc-leaderboard-set-images` `gc-leaderboard-set-releases` `gc-leaderboard-set-member-localizations` `gc-leaderboard-set-versions` `gc-challenges` `gc-challenge-localizations` `gc-challenge-images` `gc-challenge-versions` `gc-challenge-version-releases` `gc-activities` `gc-activity-localizations` `gc-activity-images` `gc-activity-versions` `gc-activity-version-releases` `gc-enabled-versions` `gc-app-versions`

### Game Center — Matchmaking (5)
`gc-matchmaking-rules` `gc-matchmaking-rule-sets` `gc-matchmaking-rule-set-tests` `gc-matchmaking-queues` `gc-matchmaking-teams`

### Game Center — Players (1)
`gc-player-achievement-submissions`

### App Clips (7)
`app-clips` `clip-default-experiences` `clip-advanced-experiences` `app-clip-header-images` `app-clip-advanced-experience-images` `app-clip-default-experience-localizations` `app-clip-app-store-review-details`

### Analytics & Diagnostics (5)
`analytics-report-requests` `analytics-reports` `analytics-report-instances` `analytics-report-segments` `diagnostic-signatures`

### Alternative Distribution (6)
`alt-distribution-domains` `alt-distribution-keys` `alt-distribution-packages` `alt-distribution-package-versions` `alt-distribution-package-variants` `alt-distribution-package-deltas`

### Background Assets (6)
`background-assets` `background-asset-versions` `background-asset-upload-files` `background-asset-version-app-store-releases` `background-asset-version-external-beta-releases` `background-asset-version-internal-beta-releases`

### Webhooks (3)
`webhooks` `webhook-deliveries` `webhook-pings`

### Team & Accounts (6)
`users` `user-invitations` `sandbox-testers` `sandbox-testers-clear` `actors` `android-to-ios-mapping`

### Marketplace (2)
`marketplace-search-details` `marketplace-webhooks`

### Other (4)
`metrics` `nominations` `merchant-ids` `pass-type-ids` `end-app-availability-pre-orders`

## Output Format

```json
{"ok": true, "status": 200, "data": {...}, "brief": {"total": N, "items": [...]}}
```

## Authentication

ES256 JWT signed with .p8 key. Get credentials from App Store Connect > Users and Access > Integrations > App Store Connect API. Tokens valid 20 min, auto-refreshed.

## Regenerating the SDK

When Apple releases a new API version, download the new OpenAPI spec and run:

```bash
./scripts/regenerate_sdk.sh /path/to/new/openapi.oas.json
```
