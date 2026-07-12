# ADR-0001: cloud-itonami-isic-5820 — RevOps-LLM を封じ込めた知能ノードとする商用CRM/サブスクリプション商取引 actor 設計

- Status: Accepted (2026-07-12)
- 関連: `cloud-itonami-isic-6209`(TicketRouter-LLM ⊣ TicketGovernor、直接の
  手本)、`cloud-itonami-isic-6920`(double-guard を dedicated boolean で
  行う設計の直接の教訓元)、`kotoba-lang/crm`(この build で新規切り出しした
  技術commons)、langgraph-clj ADR-0001

## 課題

ISIC Rev.4 5820「Software publishing」は広いコードであり、単純な
relabeling を避けるため、**商用 CRM/サブスクリプション商取引 SaaS
プラットフォーム事業**(Salesforce/HubSpot クラス)に narrow した。
RevOps-LLM に stage 確定・割引適用・feature 有効化を直接行わせると、
価格ガバナンス違反・未払い entitlement の付与・収益認識の誤りのリスクが
ある。

## 決定

RevOps-LLM は proposal のみを返す助言者とし、独立した
SubscriptionGovernor がすべての stage 遷移・開示・紛争解決を検閲する。
**単一不変条件**: RevOps-LLM は、SubscriptionGovernor が拒否する
stage 遷移確定・開示・紛争解決を決して行わない。

domain-unique HARD チェック2つ(この fleet で新規): `entitlement-scope-
gate`(closed-won 化が account の有効な subscription tier を超える
feature/seat を有効化しようとしたら拒否 — capability/license 妥当性
チェックという新しい check kind)、`stage-sequence-gate`(パイプライン
stage のスキップ/逆行を拒否 — 状態遷移妥当性チェックという新しい check
kind、`kotoba-lang/crm`の`kotoba.crm.pipeline`に汎用ロジックとして切り出し)。

`double-close-gate` は 6920 の教訓(status-lifecycle バグ)を踏まえ、
`:status`/`:stage` 値ではなく専用 `:closed?` boolean で二重クローズを
防止する。

`revenue-mismatch-imminent` gate(SOFT、常時 escalate)は
`kotoba.crm.revrec`(FASB ASC 606 / IASB IFRS 15 straight-line recompute、
純粋な ground-truth recompute — 6492/6920 が確立した family の拡張)を
使い、closed-won の計上額が recompute と乖離したら確信度に関わらず
人間承認へ回す。

## kotoba-lang/crm の新規切り出し

この build で `kotoba-lang/crm` を新規作成し、`kotoba.crm.pipeline`
(汎用 stage 遷移検証)と `kotoba.crm.revrec`(straight-line revenue
recognition recompute)をこの actor 固有ロジックからの汎用抽出として
配置した。将来の marketing-automation/customer-service 系 sibling
actor が同じロジックを再導出せず再利用できるようにするための、
「業務モデルごとに `cloud-itonami-*` を分け、技術的な共通は kotoba-lang
に置く」という明示的な設計方針への準拠。

## Consequences

- (+) `kotoba-lang/industry` registry 5820 スロットが実装へ昇格。
- (+) narrowing 判断を明記(software publishing 全体の relabeling を回避)。
- (+) `entitlement-scope-gate`/`stage-sequence-gate` はこの fleet の
  check-kind 語彙への genuine な追加。
- (+) `kotoba-lang/crm` は最初の CRM 系技術commonsで、marketing/service
  sibling actor が同じ pipeline/revrec ロジックを再利用できる。
- (+) `MemStore` ‖ `DatomicStore` parity は `test/crm/store_contract_test.
  clj` で証明。
- (-) R0 は3割引権限tier・3subscriptionティア・3出典クラスのみ、線形
  5-stageパイプラインのみ(ブランチ/並行stageは対象外)。
- (-) revenue recognition は straight-line のみ、usage-based billing・
  contract modification・multi-element allocation は対象外。
- (-) marketing-automation / customer-service hub は sibling actor として
  未着手(README/business-model.md にロードマップとして明記)。

## 代替案と不採用理由

| Option | Verdict | Reason |
|---|---|---|
| ISIC 5820 を「software publishing 全般」のまま実装 | ❌ | 6209/6612等が確立した narrowing 規律に反する。scope が際限なく広がる |
| CRM/marketing/service を1つの actor にまとめる | ❌ | オーナー指示「business modelごとに設計」に反する。この fleet の
  one-business-model-per-actor 規律にも反する |
| pipeline/revrec ロジックを actor 内に private に留める | ❌ | オーナー指示「技術的な共通は kotoba-lang org に」に反する。将来の
  sibling actor が同じロジックを再導出することになる |
| double-close を `:stage` 値だけで判定 | ❌ | ADR-2607071320 で確認済みの status-lifecycle バグと同じ罠 |

## References

- `cloud-itonami-isic-6209/docs/adr/0001-architecture.md`(直接の手本)
- ADR-2607071351(`cloud-itonami-isic-6920`、double-guard 設計の教訓元)
- `kotoba-lang/crm`(この build で新規切り出しした技術commons)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
