---
name: apple-connect
description: >-
  Direct access to Apple's App Store Connect and Apple Search Ads APIs — no
  relay. Use when a workflow needs apps, builds, sales, campaigns, keywords or
  reports straight from Apple with an apple_asc or apple_asa connection.
translations:
  zh:
    name: "Apple 直连"
    description: >-
      直连 Apple 的 App Store Connect 与 Apple Search Ads API，不经任何中转。当工作流需要用 apple_asc 或 apple_asa 连接直接读取 App、构建版本、销售、广告系列、关键词或报表时使用。
  en:
    name: "Apple Connect"
    description: >-
      Direct access to Apple's App Store Connect and Apple Search Ads APIs — no relay. Use when a workflow needs apps, builds, sales, campaigns, keywords or reports straight from Apple with an apple_asc or apple_asa connection.
  zhHant:
    name: "Apple 直連"
    description: >-
      直連 Apple 的 App Store Connect 與 Apple Search Ads API，不經任何中轉。當工作流程需要用 apple_asc 或 apple_asa 連線直接讀取 App、建置版本、銷售、廣告系列、關鍵字或報表時使用。
  ja:
    name: "Apple 直接連携"
    description: >-
      Apple の App Store Connect と Apple Search Ads API に中継なしで直接アクセスします。apple_asc または apple_asa 接続でアプリ、ビルド、売上、キャンペーン、キーワード、レポートを直接取得する場合に使用します。
  ko:
    name: "Apple 직접 연결"
    description: >-
      중계 없이 Apple의 App Store Connect 및 Apple Search Ads API에 직접 접근합니다. apple_asc 또는 apple_asa 연결로 앱, 빌드, 매출, 캠페인, 키워드, 리포트를 직접 가져올 때 사용하세요.
  es:
    name: "Conexión directa con Apple"
    description: >-
      Acceso directo a las API de App Store Connect y Apple Search Ads, sin intermediarios. Úsalo cuando un flujo necesite apps, builds, ventas, campañas, palabras clave o informes directamente de Apple con una conexión apple_asc o apple_asa.
  fr:
    name: "Connexion directe Apple"
    description: >-
      Accès direct aux API App Store Connect et Apple Search Ads, sans relais. À utiliser quand un workflow a besoin des apps, builds, ventes, campagnes, mots-clés ou rapports directement depuis Apple via une connexion apple_asc ou apple_asa.
  de:
    name: "Apple-Direktzugriff"
    description: >-
      Direkter Zugriff auf Apples App-Store-Connect- und Apple-Search-Ads-APIs — ohne Relay. Verwenden, wenn ein Workflow Apps, Builds, Umsätze, Kampagnen, Keywords oder Berichte direkt von Apple über eine apple_asc- oder apple_asa-Verbindung benötigt.
  vi:
    name: "Kết nối trực tiếp Apple"
    description: >-
      Truy cập trực tiếp API App Store Connect và Apple Search Ads của Apple, không qua trung gian. Dùng khi quy trình cần app, bản dựng, doanh thu, chiến dịch, từ khóa hoặc báo cáo trực tiếp từ Apple qua kết nối apple_asc hoặc apple_asa.
  ar:
    name: "اتصال Apple المباشر"
    description: >-
      وصول مباشر إلى واجهات App Store Connect وApple Search Ads من Apple دون وسيط. يُستخدم عندما يحتاج سير العمل إلى التطبيقات أو الإصدارات أو المبيعات أو الحملات أو الكلمات المفتاحية أو التقارير مباشرة من Apple عبر اتصال apple_asc أو apple_asa.
  pt:
    name: "Ligação direta à Apple"
    description: >-
      Acesso direto às API App Store Connect e Apple Search Ads da Apple — sem intermediários. Use quando um fluxo precisar de apps, builds, vendas, campanhas, palavras-chave ou relatórios diretamente da Apple com uma ligação apple_asc ou apple_asa.
---

# apple-connect

Direct Apple API access for workflow steps. Two services, one skill:

| Service | API | Connection | Auth shape |
|---|---|---|---|
| App Store Connect | `api.appstoreconnect.apple.com/v1` | `apple_asc` | ES256 JWT **is** the bearer (≤20 min) |
| Apple Ads (v1) | `api.ads.apple.com/v1` | `apple_asa` | ES256 client_secret → OAuth token → bearer + `X-Ap-Context: adAccountId=<id>;` (resolved from `/v1/acls`; `org_id` may hold the legacy orgId or the new adAccountId) |

The legacy `api.searchads.apple.com/api/v5` surface is being retired by Apple;
this skill targets the Apple Ads Platform API v1 exclusively.

No relay is involved: requests go straight to Apple. This is the public
replacement for the team-internal `dingyue-asa` / `dingyue-appleconnect`
relays, which remain team-only.

## Credentials

Preferred: the user connects **Apple App Store Connect** (`apple_asc`) or
**Apple Search Ads** (`apple_asa`) in the credential centre. The runtime then
injects `WF_CREDENTIAL_APPLE_ASC` / `WF_CREDENTIAL_APPLE_ASA` as decrypted
JSON envelopes and the clients pick them up automatically.

Fallback for local runs: individual variables (`APPLE_ISSUER_ID`,
`APPLE_KEY_ID`, `APPLE_PRIVATE_KEY_PEM`, `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`,
`APPLE_ORG_ID`). Missing values fail with an error that names exactly what to
set or connect.

## CLI

```bash
python3 scripts/cli.py asc apps --limit 10
python3 scripts/cli.py asc request GET /v1/apps/123/builds --query limit=5
python3 scripts/cli.py asa campaigns
python3 scripts/cli.py asa report-campaigns --start 2026-07-01 --end 2026-07-31
python3 scripts/cli.py asa request POST /v1/adgroups/query --body '{"pagination":{"offset":0,"pageSize":50}}'
python3 scripts/cli.py asa request GET /v1/acls
```

All output is JSON on stdout; errors are JSON on stderr with a non-zero exit.
The generic `request` subcommands expose both APIs in full — the named
shortcuts only cover the highest-frequency reads.

## Library

```python
from apple_api import AscClient, AsaClient

asc = AscClient.from_env()
apps = asc.paginate("/v1/apps", {"limit": "200"})

asa = AsaClient.from_env()
report = asa.request("POST", "/v1/reports/apps/campaigns/query", body={...})
```

`AscClient.paginate` follows `links.next`. Both clients cache their tokens and
re-mint before expiry.

## Notes for maintainers

- `cryptography` is required (present in the exec-python images). The ES256
  signer converts DER to the raw r||s JOSE form — see `_der_to_jose`; breaking
  that conversion yields signatures Apple rejects with a bare 401.
- `scripts/smoke.py` is a fully offline self-test (12 checks) covering the
  signature format, claim sets and credential-loading order. Run it after any
  change to `apple_api.py`.
- ASA API version is pinned at v5 in paths, not in the client — a version bump
  is a path change in the caller, not a code change here.
