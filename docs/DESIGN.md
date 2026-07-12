# Commercial CRM / Subscription-Commerce Actor Design

RevOps-LLM を最下層ノードに封じ込め、SubscriptionGovernor(独立系統)が
discount-authority・subscription entitlement・pipeline stage 順序・
ASC606/IFRS15 revenue recognition を検閲する構図。Salesforce/HubSpot
クラスの商用 CRM/SaaS プラットフォーム事業を ISIC Rev.4 5820(Software
publishing)に narrow して実装した、この fleet 初の CRM/サブスクリプション
商取引垂直市場。`cloud-itonami-isic-6209`(TicketRouter-LLM ⊣
TicketGovernor)の写像。

## 1. なぜ actor 層が要るのか

営業パイプラインの進行/開示は LLM で加速できるが、**最終的な確定権限を
持たせるのは危険**:

| LLM が起こしうる失敗 | 帰結 |
|---|---|
| 権限を超える割引を確定 | 収益毀損・価格ガバナンス違反 |
| account の subscription tier を超える feature/seat を有効化 | 未払い entitlement の付与 |
| pipeline stage をスキップして closed-won 確定 | 承認プロセスの空洞化 |
| 計上額が ASC606/IFRS15 の recompute と乖離したまま自動処理 | 収益認識の誤り |

## 2. OperationActor(`src/crm/operation.cljc`)

```
intake → advise → govern → decide ─┬─ commit
                                   ├─ escalate ─▶ request-approval → commit|hold
                                   └─ hold
```

## 3. SubscriptionGovernor(`src/crm/policy.cljc`)

優先順位(HARD は人間承認でも上書き不可):

1. rbac
2. **discount-authority-gate** — rep の discount-tier が要求割引率に未達なら拒否
3. **entitlement-scope-gate**(新規 check kind) — closed-won 化が account の
   有効な subscription tier を超える feature/seat を有効化しようとしたら拒否
4. **double-close-gate** — 専用 `:closed?` boolean で二重クローズを防止
   (ADR-2607071320 の status-lifecycle バグから学んだ設計)
5. **stage-sequence-gate**(新規 check kind) — `kotoba.crm.pipeline` による
   stage 遷移の正当性(スキップ不可)
6. source-provenance-gate
7. licensed-disclosure
8. 確信度フロア(SOFT)
9. **revenue-mismatch-imminent gate**(SOFT) — `kotoba.crm.revrec` の
   ASC606/IFRS15 straight-line recompute と計上額が乖離したら常に人間承認
10. dispute-request(SOFT、無条件)

## 4. SSoT(`src/crm/store.cljc`)

reps(discount-tier)・accounts(subscription-tier/active?)・
opportunities(stage/amount/discount-pct/closed?)・subscriptions
(product-tier/contract-value/term/start-date)・append-only ledger。

## 5. R0(`src/crm/facts.cljc`)

出典クラス3種 + 3段階 discount-authority tier + 3段階 subscription
feature tier + 5-stage 線形パイプライン(+1 exit stage)。

## 6. Phase 0→3(`src/crm/phase.cljc`)

`default-phase` = 1(保守的)。`dispute/request` はどの phase の `:auto`
にも入らない。

## 7. 技術的共通(`kotoba-lang/crm`)

`kotoba.crm.pipeline`(汎用 stage 遷移検証)と `kotoba.crm.revrec`
(ASC606/IFRS15 straight-line recompute)はこの actor 固有ロジックではなく
kotoba-lang の技術commonsとして切り出し、将来の marketing-automation /
customer-service 系 sibling actor が同じ pipeline・revenue-recognition
ロジックを再導出せず再利用できるようにしてある。
