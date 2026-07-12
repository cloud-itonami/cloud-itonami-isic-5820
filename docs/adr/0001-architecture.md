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

## Addendum(2026-07-12): `src/crm/dashboard.cljc` — book-wide 集計
ダッシュボード(pipeline funnel / conversion rate / revenue rollup)

### 課題

`crm.report`(=`render-opportunity`)は「1 opportunity・disclosure-tier
column-gated の開示」という単一レコード概念であり、営業/ops 側が求める
「パイプライン全体の見通し」(ステージ分布・コンバージョン率・収益
rollup)には別概念が要る。オーナー指示「Sales pillar を
reporting/dashboard で深める」への対応。

### 決定

`kotoba-lang/crm` に新規切り出しした `kotoba.crm.funnel`(集計 stage-
counts/reached-counts/conversion-rate、pure・storage-agnostic)を
この actor 固有の `crm.facts/pipeline-stage-order` /
`crm.facts/exit-stages`(`stage-sequence-gate` と同一のステージ語彙)の
上に適用し、`src/crm/dashboard.cljc` として実装した。収益 rollup は
`kotoba.crm.revrec/recognized-revenue-to-date` を全 ACTIVE
subscription に対して再計算・合算する — `crm.policy` の
`revenue-mismatch-imminent?` gate が既に依拠している同じ
ground-truth recompute discipline の再利用であり、キャッシュ値や
proposal 由来の数字を信用しない。

`crm.store`(`Store` protocol)に `all-accounts` を新規追加した —
book-wide の収益 rollup は全 account を列挙する必要があるが、既存
protocol には account 単体 lookup(`account`)しか無かったため。
`all-reps`/`all-opportunities` と同型の追加で、`MemStore`/
`DatomicStore` 双方に実装し、`test/crm/store_contract_test.clj` の
`read-parity`/`datomic-empty-store-is-usable` に parity assertion を
追加して契約を保った(後方互換な追加、既存メソッドの変更なし)。

### Governance(RBAC)判断とその理由

`crm.dashboard` は新規 op `:pipeline/dashboard-query` として
`crm.policy` の `permissions` テーブルに **`:sales-manager` のみ**を
許可する形で登録した(rbac 違反時は他の read op 同様 HOLD)。判断根拠:

- 単一 opportunity/account の disclosure-tier column gating
  (`licensed-disclosure` check、`:disclosure/query`)とは性質が異なる
  — dashboard は特定 column の開示ではなく、**book 全体**(全
  account・全 opportunity)の集計値そのものが payload であり、
  `:account-holder` に許すと「自分の契約」を超えて他 account の
  パイプライン/収益状況まで推測可能になる。これは単一 account の
  column 超過よりも広い露出であり、`:account-holder` には一切許可
  しない(保守的判断)。
- `:rep` の既存 permission は `:opportunity/transition-stage` のみで
  book-wide な読み取り権限をこのテーブルに一つも持たない。dashboard
  を rep にまで広げる先例が無い以上、新規に広げず `:sales-manager`
  のみに絞った(「先例が無ければ保守的に決める」という本タスクの
  指示どおり)。
- 一方で HARD チェック 2-6(discount-authority/entitlement-scope/
  double-close/stage-sequence/source-provenance)は
  `:opportunity/transition-stage` 専用、`licensed-disclosure` は
  `:disclosure/query` 専用であり、いずれも `:pipeline/dashboard-query`
  には作用しない(集計 view は個々の record を書き換えも開示もしない
  ため、単一不変条件で守るべき対象が rbac のみで足りる)。
- `crm.phase/read-ops` にも `:pipeline/dashboard-query` を追加し、
  `:disclosure/query` と同じ「phase を問わず governor disposition
  そのまま」の read-op 扱いにした — 書き込みではないため phase の
  autonomy 段階に関係なく governor(rbac)だけが判断する。

`crm.dashboard` 自体は `crm.report/render-opportunity` と同じく
**pure レンダラー**(内部で permission を再チェックしない)——呼び出し
側が `{:op :pipeline/dashboard-query ...}` を `crm.policy/check`
(または `crm.operation` の graph)に通し、許可された後にのみ呼ぶ
という前提を docstring に明記した。

### Consequences

- (+) Sales pillar に book-wide の funnel/conversion/revenue rollup が
  追加され、`crm.report` の単一レコード開示と役割分担が明確化。
- (+) `kotoba.crm.funnel` の R0 honest-scope(exit-stage 除外ポリシー・
  time-series 非対応)をそのまま継承し、独自に再定義しない。
- (+) `all-accounts` は `MemStore`/`DatomicStore` 双方に実装され
  parity test を通過 — protocol 拡張が契約を壊さないことを証明。
- (-) `:pipeline/dashboard-query` は現状 `:sales-manager` のみ——
  `:rep` が自分の担当分だけを見る絞り込みビューは本 R0 の対象外
  (将来 sibling 機能として ADR 化が必要になった場合の拡張ポイント)。
- (-) revenue rollup は `kotoba.crm.revrec` の R0 制約(straight-line
  のみ)をそのまま継承するため、usage-based billing・multi-element
  allocation は対象外。

### References(追加)

- `kotoba-lang/crm` `src/kotoba/crm/funnel.cljc`(この addendum で
  新規依存に追加した集計 commons)

## Addendum(2026-07-13): `src/crm/llm_realmodel.clj` — 実モデル呼び出し
adapter(honest gap 解消、ただし実呼び出し自体は未検証)

### 課題

`src/crm/llm.cljc` の RevOps-LLM advisor は SEALED/決定論的な mock
(`crm.llm/mock-advisor`/`crm.llm/infer`)であり、実際の言語モデルを
一切呼ばない。これは本番運用へ向けた既知の gap であり、後で operator が
実クレデンシャルを与えたときに actor を実モデルへ向けられる経路が無かった。
**本 sandbox には実モデル API のクレデンシャルが一切無い**
(`ANTHROPIC_API_KEY`/`OPENAI_API_KEY` 等、env に確認済みで無し)ため、
この addendum の目的は「実呼び出しを行う」ことではなく「operator が後で
クレデンシャルを与えたときに動く ADAPTER を配線する」ことに限定される。

### 決定

`orgs/gftdcojp/cloud-itonami`(同一 lineage/org の別 repo、より広い
"business-os" cloud-itonami 本体)の `cloud_itonami.runtime` 名前空間が
既に確立していた `ITO_MODEL_PROVIDER`/`ITO_MODEL_URL`/`ITO_MODEL`/
`ITO_MODEL_API_KEY` という env-var 駆動の convention をそのまま踏襲し
(非互換な新規 shape を発明しない)、`ISIC5820_`-prefix 版として
`src/crm/llm_realmodel.clj`(JVM-only、`crm.http`/`crm.file-store` と
同じ理由——実 HTTP I/O は kotoba-wasm/clojurewasm/cljs/nbb 層に
portable primitive が無いインフラ glue)に実装した。

**graph-facing contract は一切変更しない**: `crm.llm.cljc` は既に
`crm.llm/llm-advisor`(任意の `langchain.model/ChatModel` を
`crm.llm/Advisor` protocol でラップする既存の汎用関数)を持っていた
——`crm.operation/build`の`:advise`ノードが呼ぶ shape・返す proposal
shape は `mock-advisor` と完全に同一。`crm.llm-realmodel/real-advisor`
は `real-chat-model`(`langchain.model/openai-model`/`anthropic-model`
——両方とも `kotoba-lang/langchain` 側で既に汎用実装・テスト済み——を
provider に応じて呼び分けるだけ)を `llm-advisor` でラップして返すのみで、
`crm.llm`側のsystem-prompt・fact抽出・EDN parse ロジックを一切複製しない。

`crm.http/resolve-advisor!` が唯一のトリガー: `$ISIC5820_MODEL_API_KEY`
が set かつ non-blank なら real advisor、そうでなければ既存の sealed
mock(`crm.operation/build`自身の既定と同一)——起動時に選んだモードを
`crm.llm-realmodel/preflight`(API key の値は一切含まない、`:api-key?`
boolean のみ)と共に必ず print する。`warn-ephemeral-store!` が確立した
"fail-visible" 規律をそのまま advisor 選択にも適用した。

### 検証したこと・していないこと(正直な線引き)

- ✅ `preflight` の missing/present 判定ロジック——provider 別
  (openai/anthropic/openclaw)・url/key の有無・unknown provider・
  blank env value の全パターンをクレデンシャル無しで検証
  (`test/crm/llm_realmodel_test.clj`)。
- ✅ 実際に送信する HTTP リクエストの wire shape(method・bearer
  header・JSON body の model/messages フィールド)と、レスポンス
  parse——ただし相手は**本物の実モデル API ではなく、この build 内で
  起動したローカル `org.httpkit.server` stub**(実 socket 越しの実
  HTTP round-trip。`crm.http_test.clj` が自分自身のサーバーを検証する
  のと同じ手法をクライアント側に転用)。`crm.llm/llm-advisor` ->
  `crm.llm/parse-proposal` を経由した proposal 生成、および EDN として
  parse できないモデル応答への fallback(`:noop`/confidence 0.0)経路
  まで含めて検証済み。
- ❌ **実モデル API(OpenAI/Anthropic/実際の OpenAI 互換ゲートウェイ)が
  この request shape を実際に受理し、期待通り応答するか**は、この
  sandbox にクレデンシャルが存在しないため**検証不能・未検証のまま**。
  偽の endpoint をでっち上げてもこれは証明できないので、行っていない。
  `ISIC5820_MODEL_API_KEY` を実際に設定した operator は、genuinely
  配線された adapter を得るが、その実呼び出し挙動は operator 自身が
  検証するまで未検証のままである。

### Consequences

- (+) `crm.operation`/`crm.policy`/`crm.phase` の governance 経路は
  一切変更なし——advisor の実装が mock から real に変わるだけで、
  SubscriptionGovernor の censorship・phase gate・監査台帳は完全に
  そのまま。
  (+) この org 内で2つ目(`cloud-itonami` 本体に続き)の
  `ITO_MODEL_*`/`ISIC5820_MODEL_*` 実装——同一 shape の再利用により
  将来の sibling actor(6209/6920等)が同じ pattern を再発明せず流用できる。
- (-) 実モデル呼び出し経路は end-to-end 未検証(上記)。本番投入前に
  operator が実クレデンシャルで自ら検証する必要がある。
- (-) tool-calling(構造化出力用の JSON schema tool 定義)は本 addendum
  では配線していない——`crm.llm`の既存 system-prompt は「EDN のみ返せ」
  という自然言語指示に依存しており(mock advisor と同じ contract)、
  `langchain.model`のtool-calling機構(`langchain.tool`)は今回未使用。
  実モデルの出力が安定して EDN にならない場合の改善余地として残す。

### References(追加)

- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/runtime.cljc`
  (`model-config`/`model-preflight`/`real-model`/`jvm-http-fn` ——
  この addendum が踏襲した直接の手本)
- `kotoba-lang/langchain` `src/langchain/model.cljc`(`anthropic-model`/
  `openai-model`——実際の HTTP リクエスト構築・レスポンス parse は
  ここに既に汎用実装済みで、本 addendum はこれを呼ぶだけ)
