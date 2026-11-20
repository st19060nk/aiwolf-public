# 研究ログの解析

## 準備

```bash
pip install pandas numpy
```

## 実行

```bash
python aiwolf_analysis.py "<ワールド>/aiwolf-research"
```

CSV は `<ワールド>/aiwolf-research/analysis/` に出ます。`-o` で変更できます。

## 出力されるもの

| ファイル | 内容 |
|---|---|
| `signatures.csv` | 1 行 1 測定。行動割合・平均 owner distance・測定時の内部状態 |
| `signature_distances.csv` | 指紋の総当たり距離。`kind` が `within`（同一個体）か `between`（別個体） |
| `separation_summary.csv` | ラベルごとの個体内距離・群間距離・比 |
| `free_play_behaviour.csv` | 自由行動中の行動分布（参考値） |
| `score_components.csv` | 全候補行動のスコア内訳。1 行 1 候補 |
| `selected_score_components.csv` | 選ばれた行動の成分平均 |
| `events_by_wolf.csv` | 個体ごとの出来事の回数 |
| `events.csv` | 1 行 1 出来事。`combatId` / `hostileType` を含む |
| `combat_events.csv` | 個体ごとの戦闘イベントの回数（`combatId` を持つ出来事のみ） |
| `damage_events.csv` | 被弾イベント。出どころ・区間合計・代表への帰属量・相手の数を含む |
| `damage_hits.csv` | `damage.jsonl` の全行。被弾 1 発ごとの実 HP 損失と加害者 |
| `event_state_impact.csv` | 出来事ごとの内部状態の平均変化量（適用前後の差分） |

## アブレーションの対応のある比較（Phase 16・17）

```bash
./gradlew individuality
python analysis/ablation.py research-output/individuality/separation_by_seed.csv
python analysis/ablation.py research-output/individuality/separation_by_seed.csv --metric ratioJS --csv-out paired.csv
```

条件間で出来事列と乱数列が同じ（同じシード）なので、シードを単位とした対応のある比較をします。
基準条件（既定 `state_only`）との差の平均・95 % 信頼区間・対応のある t 検定（両側、多重比較の補正なし）を出します。
t 分布は標準ライブラリで計算するので scipy は不要です（pandas と numpy だけ）。
結果の読み方は `docs/lab-notes/2026-11-14_ablation-individuality.md` を参照してください。

## 図（Figure 1〜4）

`aiwolf_analysis.py` は、何を主張するかが決まる前の汎用の表だけを出し、図は描きません
（汎用スクリプトが自動生成した図をそのまま使うと、主張と図がずれたままになりがちなため）。

一方、主張が決まっている研究質問（天井効果・成分分解・個体性・アブレーション）については、
その主張のための図を専用のスクリプトで描きます。依存は pandas と numpy だけで、
図は自作の `svgplot.py` が SVG で書きます（matplotlib は不要。GitHub や実験ノートでそのまま表示できます）。

```bash
./gradlew saturation
./gradlew individuality
./gradlew demoEstimate
python analysis/make_figures.py --out docs/figures/<日付>
```

| スクリプト | 図・表 | 入力 |
|---|---|---|
| `state_saturation.py` | Figure 1（内部状態の時間変化）、到達時刻の表 | `research-output/state-saturation/` |
| `score_contribution.py` | Figure 2（スコア成分）。実機の `decisions.jsonl`（版 2 以降）にも使える | `demo_a_breakdown.csv` または `decisions.jsonl` |
| `individuality.py` | Figure 3（個体内 vs 個体間）、分離比の表 | `separation_by_seed.csv` |
| `ablation.py` | Figure 4（条件ごとの分離比）、対応のある比較 | `separation_by_seed.csv` |
| `make_figures.py` | Figure 1〜4 をまとめて作り直す | 上のすべて |

## Human Study のログ（Phase 20〜24）

セッション（`/wolfai session start`）とフェーズ（`/wolfai phase set`）で区切って集計します。
ログの項目は `docs/research-log-schema.md` を参照してください。依存は pandas と numpy だけです。

```bash
python analysis/player_behaviour.py "<world>/aiwolf-research"
python analysis/ai_availability.py "<world>/aiwolf-research"
```

| スクリプト | 出力 | 内容 |
|---|---|---|
| `player_behaviour.py` | `phase_segments.csv` | セッションとフェーズの区間（開始・終了・長さ・終わり方） |
| | `player_events.csv` | プレイヤーの行動 1 件 1 行（`details` は `detail_*` 列に展開） |
| | `player_event_rates.csv` | セッション × フェーズ × 飼い主 × 個体ごとの行動の回数と 1 分あたりの回数（`perMin_*`） |
| | `player_samples_by_phase.csv` | 同じ単位の、飼い主とオオカミの距離・そばにいた割合（`--near`、既定 7 blocks）・移動・敵の有無・AI の稼働割合 |
| | `feed_check.csv` | `events.jsonl` の FEED の重複検査（下記） |
| `ai_availability.py` | `ai_suspensions.csv` | AI の停止区間。理由が途中で変わった停止は理由ごとに分け、同じ `suspensionId` を付ける |
| | `ai_availability_by_phase.csv` | フェーズごとの長さ・理由別の停止時間・稼働時間（`availableMinutes`）・決定の数・行動の割合 |

共通の読み込み（区間の作り方など）は `study_logs.py` にあります。

* **分母に注意**：`player_event_rates.csv` の 1 分あたりはフェーズの長さで割っています（プレイヤーの行動は AI の停止と関係なく起きるため）。
  オオカミの行動をフェーズ間で比べるときは、`ai_availability_by_phase.csv` の `availableMinutes`（AI が決定できた時間）を分母にしてください。
  座っていた時間が長いフェーズは、行動の回数が少なく見えるだけです。
* **閉じていない区間**：`SESSION_STOP` や `AI_RESUMED` がない区間（クラッシュなど）は、その実行のログの最後の時刻で閉じ、
  `endReason` / `endEvent` を `END_OF_LOG` にします。実際の長さはそれより長いかもしれません。
* `decisionsPerAvailableMinute` は決定間隔 2 秒なら最大約 30 です。大きく下回るフェーズは、記録されていない停止がないか確認してください。
* Demo Mode の行（`demoMode: true`）は読み込み時に除きます。

### セッションのデータ品質の検査（Phase 25D/E）

1 セッションが終わったら、解析の前にまずこれを実行します（手順は `docs/HUMAN_STUDY_DRY_RUN.md`）。

```bash
python analysis/check_session_integrity.py "<world>/aiwolf-research" <sessionId>
python analysis/check_session_integrity.py "<world>/aiwolf-research" latest --forbidden-player-name Researcher
```

`latest` は最後に始まったセッションです。`--forbidden-player-name` / `--forbidden-player-uuid` にテスト環境のプレイヤーの名前と UUID を渡すと、
それ（UUID は先頭部分も）がログのどこかに出ていないかも直接探します（何度でも指定可）。`--expect-phases` で必須のフェーズを変えられます。

検査の項目：セッションの開始・終了、版（`modVersion` / `buildRevision` / `protocolVersion` / `parameterSetVersion`）、
フェーズの区間、各ログの件数と `worldTime` / `logSequence` の範囲、`logSequence` の重複・逆行（欠番は数えるだけ）、
オオカミごとの決定数・決定率・行動の分布（フェーズ別も）、AI の稼働時間と停止の理由、プレイヤーのサンプリング（期待数・実数・網羅率・
間隔の中央値と 95 パーセンタイル・最大の欠損）、JSON として読めない行と、ないファイル、プレイヤーの行動の件数、FEED の二重記録、個人情報の混入。

最後に `PILOT DATA QUALITY: PASS` / `WARNING` / `FAIL` を 1 行で出します（終了コード：PASS 0 / WARNING 1 / FAIL 2）。

| 判定 | 条件 |
|---|---|
| **FAIL**（解析に使えない） | SESSION_START / SESSION_STOP が 1 件ずつでない、開始 ≥ 終了、開始と終了が別の runId |
| | SESSION_START に版の項目（`REQUIRED_VERSION_FIELDS`）がない |
| | `logSequence` の重複、ファイル内での逆行 |
| | 1 ファイルで JSON として読めない行が `MAX_PARSE_ERRORS_PER_FILE`（1）を超える |
| | 主要ログ（`sessions` / `decisions` / `player-samples`）にこのセッションの行が 1 件もない |
| | 同じ個体・同じ tick の FEED / PLAYER_FEED が 2 件以上（二重記録の再発） |
| | 個人情報：`wolfUuid` 以外の UUID の形（ハイフンあり・なし）、メールアドレス・IP アドレスの形、指定したプレイヤー名・UUID |
| **WARNING**（使えるが確認が要る） | サンプリングの網羅率 < `MIN_SAMPLING_COVERAGE`、または `MAX_SAMPLE_GAP_SECONDS` を超える欠損 |
| | AI の稼働割合 < `MIN_AI_ACTIVE_RATIO`、稼働 1 分あたりの決定 < `MIN_DECISIONS_PER_ACTIVE_MINUTE` |
| | 必須のフェーズ（`EXPECTED_PHASES`）がない、TRAINING < `MIN_TRAINING_MINUTES` |
| | プレイヤーの行動が 0 件、`buildRevision` が UNKNOWN、`buildDirty` が true、サーバ停止でセッションが閉じた |
| | `logSequence` のない行（版 3 より前のビルド。古いログは許容し、順序は `worldTime` で近似）、読めない行が 1 行だけ |
| | `events` / `player-events` / `ai-availability` のファイルがない（`damage` / `signatures` は戦闘や測定がなければ作られないので注記だけ） |

閾値はスクリプト先頭の定数です。

| 定数 | 値 | 理由 |
|---|---|---|
| `EXPECTED_PHASES` | BASELINE, TRAINING, MEMORY_TEST, EVALUATION | Study 2 の手順で必ず通るフェーズ |
| `MIN_TRAINING_MINUTES` | 10 分 | 育成が短すぎると経験の差が出ない（天井効果のノートでは 15 分前後で状態が動き切る） |
| `SAMPLE_INTERVAL_TICKS` | 20 | `AiWolfParameters.PLAYER_SAMPLE_INTERVAL_TICKS`（1 秒） |
| `MIN_SAMPLING_COVERAGE` | 0.95 | 1 秒ごとのはずなので、5 % を超える欠けは記録の問題 |
| `MAX_SAMPLE_GAP_SECONDS` | 10 秒 | 1 秒ごとのはずなので、10 秒の空白は明らかな欠損（ラグやチャンクの読み込みで数秒は起こりうる） |
| `MAX_PARSE_ERRORS_PER_FILE` | 1 | 書き込み途中で切れた最後の 1 行は起こりうる。それ以上はファイルの破損 |
| `MIN_AI_ACTIVE_RATIO` | 0.80 | 座っている・リードの時間が 2 割を超えると、行動の割合の比較が難しくなる |
| `MIN_DECISIONS_PER_ACTIVE_MINUTE` | 20 | 決定間隔 2 秒なら約 30。大きく下回るなら記録されていない停止がある |

**個人情報の検査とオオカミの UUID**：決定・出来事・被弾のログは既存との互換のためオオカミのエンティティ UUID
（`wolfUuid`、記憶 ID `<wolfUuid>#m<n>` の中も）を残しています。検査は、同じ行の集まりの `wolfUuid` に現れた UUID だけを許し、
それ以外の UUID の形はプレイヤー UUID の可能性があるとして FAIL にします。

### FEED の重複（255a617 より前のデータ）

255a617 より前は、餌やり 1 回が `events.jsonl` に FEED 2 件（同じ個体・同じ `worldTime`）として記録されていました。
`player_behaviour.py` はこれを検査し、`feed_check.csv` に `sameTickDuplicates`（重複数）・`preFixRows`（封筒のない修正前の行）・
`feedDeduplicated`（数え直した回数）を出します。回数は数え直せますが、**その個体の内部状態・記憶・選好は 2 倍の餌やりを経験した状態**なので、
学習の結果を使う解析では修正前に育てた個体を除外するか、別に扱ってください。

テスト：`python -m unittest discover -s analysis -p "test_*.py"`

## 解析上の注意

### damage の定義

`damage.jsonl` の `actualDamage` は **`EFFECTIVE_HEALTH_LOSS`**、つまり
`(health + absorption)` の攻撃前後の差です。攻撃の威力ではなく、防具・エンチャント・
Resistance の軽減をすべて反映した実際の損失です。`healthLoss` 列に HP のみの損失も
入っているので、Absorption を含めない定義で解析し直すこともできます。

各行に `damageDefinition` が入っています。スクリプトは複数の定義が混在していれば警告します
（実験の途中で定義を変えた場合、そのデータは比較できません）。

### 被弾の帰属について

同じ 40 tick のあいだに複数の相手から攻撃を受けた場合、研究イベントは 1 件だけ発火し、
`hostileId` は次の規則で選ばれた代表です。合計ダメージが最大の相手 → 同点なら最後に
攻撃してきた相手 → それも同点なら識別子の辞書順。`damageSourceCount` が 2 以上の行では、
`damageTotal` と `damageAttributed` の差が代表以外の相手から受けた量です。
「その相手から実際に受けた総量」を厳密に集計したい場合は、この差を考慮してください。

`damageOrigin` が `environment` の行は落下・溶岩・炎などで、戦闘記録は作られていません
（`combatId` も `hostileId` も付きません）。戦闘の頻度を数えるときは除外してください。


### 戦闘イベントの重複発火チェック

スクリプトは同一 `combatId` から 2 件以上のイベントが出ていないかを検査し、出ていれば警告します。
1 回の戦闘から複数のイベントが発火すると、その経験が state・エピソード記憶・学習済み選好の
3 経路すべてに重複して入り、1 回の出来事から過剰に学習します。
警告が出た場合、その個体のデータは解析から除外するか、原因を特定してから再取得してください。


### 自由行動の分布を個体間で直接比べてはいけない

`free_play_behaviour.csv` は「その個体が実際に何をしていたか」ですが、
個体ごとに置かれていた状況が違います。よく撫でられた個体が飼い主の近くにいたのは、
愛着が育ったからなのか、単に飼い主がそこにいたからなのか区別できません。

**個体間の比較には `signatures.csv` を使ってください。** こちらは全個体を
まったく同一の標準文脈に置いて測っているので、差を履歴に帰属できます。
自由行動の分布は、標準文脈での傾向が実際のプレイでも現れているかを
確認する補助データとして使うのが適切です。

### 個体内距離はサンプリング誤差の指標

同じ個体を同じ `sessionIndex` で測ると結果は完全に一致します（再現性の担保）。
個体内距離を意味のある値にするには、**測定ごとに異なる `sessionIndex`** が必要です。
`/wolfai signature` はワールド時刻をセッション番号に使うので、
時間をおいて 2 回以上測れば自動的に別セッションになります。

### 群間距離 / 個体内距離の比

`separation_ratio` が 2 を超えていれば、群間の差は個体内のばらつきより
明確に大きいと言えます。シミュレーションでは adaptive 条件で約 13、
対照の static 条件で約 1.1 でした。**これは検定ではなく方向性の確認**です。
論文に載せるなら、個体数を確保したうえで適切な検定を行ってください。
