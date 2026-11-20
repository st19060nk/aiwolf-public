# 研究ログのスキーマ（2026-11-14 時点、Phase 25）

研究ログはワールドの `aiwolf-research/` に JSONL（1 行 1 レコード）で書かれます。
Demo Mode 中のログは `aiwolf-research/demo/` に分かれ、各行の先頭に `"demoMode":true` が付きます（本実験の解析に混ぜないこと）。

| ファイル | 1 行の単位 | スキーマ番号の項目 | 現在の版 |
|---|---|---|---|
| `decisions.jsonl` | 行動の決定 1 回 | `decisionLogSchemaVersion` | **3** |
| `events.jsonl` | 経験（出来事）1 件 | `schemaVersion` | **4** |
| `damage.jsonl` | 被弾 1 発 | `schemaVersion`（出来事ログと共通の番号） | 4 |
| `signatures.jsonl` | `/wolfai signature` の測定 1 回 | — | — |
| `sessions.jsonl` | セッションの開始・終了・フェーズ変更、実験者の操作 1 件 | `sessionLogSchemaVersion` | **2** |
| `player-events.jsonl` | プレイヤーの行動 1 件 | `playerLogSchemaVersion` | **2** |
| `player-samples.jsonl` | 飼い主とオオカミの関係のサンプル 1 件（セッション中、1 秒ごと × 研究個体） | `playerLogSchemaVersion` | **2** |
| `ai-availability.jsonl` | 研究 AI の停止・再開・停止理由の変更 1 件（変化したときだけ） | `aiAvailabilityLogSchemaVersion` | 1 |

## 全行に共通の項目（封筒、`logEnvelopeVersion` 3）

`ResearchLogger` が書き出す直前に、**すべてのファイルのすべての行の先頭に**次の 5 項目を付けます（`LogEnvelope.envelope`）。
各ログの版番号は変えていません（封筒は行の意味を変えないため）。

| 項目 | 内容 |
|---|---|
| `logEnvelopeVersion` | 封筒の版。現在 **3**（版 1 は `runId` だけ。この項目がない行は版 1 以前） |
| `runId` | サーバ起動 1 回につき 1 つ。形式 `run-20261114T071530Z-a1b2c3`（UTC の起動時刻 + 乱数 6 桁。個人を特定する情報は含まない） |
| `sessionId` | 実験セッションの ID（`/wolfai session start <label>`）。形式 `session-20261114T120000Z-a1b2c3`。セッションの外では JSON の `null` |
| `phaseId` | セッション内のフェーズ（`BASELINE` / `TRAINING` / `MEMORY_TEST` / `EVALUATION` / `FREE_PLAY`）。未設定・セッション外では `null` |
| `logSequence` | 版 3 から。1 回のサーバ起動（`runId`）の中で、**ファイルの種類と Demo Mode をまたいで**記録した順に 1 から振る通し番号。サーバを再起動すると 1 に戻る（`runId` が変わる） |

項目は省略せず常に書く（`null` を含む）ので、解析側で列がそろいます。

**複数のファイルを時系列に並べるときは `runId` → `logSequence` の順にしてください。** `worldTime` も残っていますが、
同じ tick に別のファイルへ書かれた行（例：同じ tick の決定と出来事）の前後は `worldTime` では分かりません。
`logSequence` は採番とバッファへの追加を 1 つのロックの中で行うので、各ファイルの中では行の順と番号の順が必ず一致し、
番号の重複もありません。欠番は、書き込みに失敗した行か、別のディレクトリ（`demo/`）の行です。
セッションはワールドに保存されません。サーバを止めると `SESSION_STOP`（`reason: SERVER_STOPPING`）が書かれ、再起動後は新しいセッションを始めます。

## 書き込みの順序と停止時の扱い（Phase 25 P0-1）

行はメモリ上のバッファに積み、30 秒ごとかバッファが一杯になったときに IO スレッドで追記します。
書き込みは**前の書き込みが終わってから次を書く**形で 1 列に並べるので、同じファイルへの追記が同時に走ったり、順序が入れ替わったりしません。
サーバ停止時は「最後のバッファを書き込みに回す → それまでの書き込みがすべて終わるのを待つ → 片付け」の順に行います。
30 秒待っても終わらない場合はサーバのログに時間切れと未完了の書き込みの数を出し、書き込み自体は取り消さずに続けます。

## Human Study のログ（Phase 20〜24）

時刻はすべて決定ログ・出来事ログと同じワールド時刻 `worldTime` [ticks]（20 ticks = 1 秒）です。
セッション中の行には `elapsedTicksFromSessionStart` も付きます。`runId` / `sessionId` / `worldTime` で決定ログと結合できます。

### `sessions.jsonl`

`recordType` で 2 種類に分かれます。

| `recordType` | `event` | 主な項目 |
|---|---|---|
| `session` | `SESSION_START` | `targetSessionId` / `sessionLabel` / `sessionStartWorldTime`、**版**（`modVersion` / `buildRevision` / `buildDirty` / `protocolVersion` / `parameterSetVersion`、版 2 から）、割付（`assignmentId` / `conditionOrder` / `assignmentTableHash` / `defaultCondition`）、`ownerSubjectId` / `researchWolves` |
| `session` | `PHASE_CHANGED` | `fromPhase` / `toPhase`（解除なら `toPhase` がない） |
| `session` | `SESSION_STOP` | `reason`（`COMMAND` / `SERVER_STOPPING`）/ `lastPhase` / `durationTicks` |
| `study_control` | `ASSIGNMENT_SET` / `ASSIGNMENT_TABLE_LOADED` / `ASSIGNMENT_CLEARED` | `assignmentId` / `conditionOrder` / `assignmentTableHash`（割付表は **SHA-256 のハッシュだけ**を記録） |
| `study_control` | `MEMORY_CONDITION_CHANGED` | `fromCondition` / `toCondition` / `learningFrozen` と、有効な機構（`memoryEnabled` / `episodicMemoryEnabled` / `spatialMemoryEnabled` / `stateLearningEnabled` / `preferenceLearningEnabled`） |
| `study_control` | `SNAPSHOT_SAVED` / `SNAPSHOT_RESTORED` / `EVALUATION_STARTED` / `EVALUATION_STOPPED` | `wolfSubjectId` / `experimentalCondition` と同じく有効な機構 |

`session` の行は、封筒の `sessionId` とは別に、対象のセッション ID を `targetSessionId` に書きます
（開始・終了の行では、封筒の値だけだと境界が分かりにくいため）。

#### SESSION_START の版の項目（Phase 25A）

| 項目 | 内容 |
|---|---|
| `modVersion` | `gradle.properties` の `mod_version` |
| `buildRevision` | ビルドした Git の commit（短縮 7 桁）。Gradle の `generateBuildInfo` がビルド時に jar へ埋め込む（実行時に git は呼ばない）。Git 情報のないビルドでは **`UNKNOWN`**（省略しない） |
| `buildDirty` | ビルド時に未コミットの変更があったか（`"true"` / `"false"` / `"UNKNOWN"`）。`"true"` なら `buildRevision` だけではコードを再現できない |
| `protocolVersion` | Human Study の**実験手順**の版（`config/aiwolf.properties` の `humanStudyProtocolVersion`、既定 1）。AI のパラメータの版とは別 |
| `parameterSetVersion` | 研究パラメータの版（`AiWolfParameters.PARAMETER_SET_VERSION`） |

`parameterSetVersion` だけでは、同じパラメータセットの中でのコード修正（例：FEED の二重記録の修正）を区別できないため、
`buildRevision` と `buildDirty` を一緒に残します。

### `player-events.jsonl` / `player-samples.jsonl`

| `eventType` | いつ | `details` |
|---|---|---|
| `PLAYER_PET` | 研究イベント PET が成立したとき（クールダウン中は書かない） | — |
| `PLAYER_FEED` | 研究イベント FEED が成立したとき | — |
| `PLAYER_ATTACK_HOSTILE` | 敵に実際にダメージを与えたとき（飛び道具は射手に帰属） | `hostileType` / `damage` / `projectile` / `distanceToWolf` / `hostileDistanceToWolf` / `wolfNearby` / `wolfInCombatWithHostile` / `hostileTargetsWolf` |
| `PLAYER_PROTECTED_WOLF` | 研究イベント PLAYER_PROTECTED_WOLF と同じ判定・同じ時刻 | `combatId` / `hostileType` |
| `PLAYER_DAMAGED` | プレイヤーが実際にダメージを受けたとき | `damage` / `attackerRole` / `attackerType` |
| `PLAYER_KILLED_HOSTILE` | プレイヤーが敵を倒したとき | `hostileType` / `wolfWasInCombatWithHostile` / `wolfWasThreatened` / `hostileDistanceToWolf` |

両ファイルの行に共通：`subjectId` / `wolfSubjectId` / `experimentalCondition` / `parameterSetVersion` / `adaptiveFeatures`、
`context`（`ownerWolfDistance` / `wolfHealthRatio` / `playerHealthRatio` / `hostileNearby` / `hostileCount` / `wolfInCombat`）。
距離は同じワールドにいないと省略されます。

`player-samples.jsonl` はセッション中だけ書かれ、ほかに `motion`（`intervalTicks` / `playerDistanceMoved` / `wolfDistanceMoved` /
`playerSpeed` / `wolfSpeed` [blocks/秒] / `playerMoving` / `wolfMoving`。0.25 blocks/秒以上を移動中とする）、
`wolfCurrentBehavior`、`wolfAiStatus`（`ACTIVE` または `SUSPENDED:<理由>`）を持ちます。

#### 移動量の基準（版 2、Phase 25 P0-4）

移動量は前回のサンプルとの差なので、**同じセッションの直前のサンプル**があるときだけ計算します。
`motion.hasBaseline` が `false` のサンプルは移動量・速さ・移動中の項目を持たず、`motion.noBaselineReason` に理由が入ります。

| `noBaselineReason` | 意味 |
|---|---|
| `FIRST_SAMPLE` | その個体の最初のサンプル（セッションの開始・終了、サーバの起動・停止、飼い主の切断で前回位置を消した直後を含む） |
| `NEW_SESSION` | 前回のサンプルが別のセッションのもの |
| `GAP` | 前回から 60 ticks（サンプル間隔の 3 倍）より長く空いた（飼い主のログアウト・個体のアンロードなど） |
| `WORLD_CHANGED` | オオカミが別のディメンションへ移った |

版 1 では、セッションを止めて 1 秒以内に次を始めると、前のセッションの最後の位置から移動量を計算していました。

**座標は書きません**（距離と移動量のスカラーだけ）。プレイヤー名・UUID・チャットも書きません。

### `ai-availability.jsonl`

研究 AI が決定できなくなった区間の**始まりと終わりだけ**を書きます（毎 tick は書かない）。

| `event` | 意味 | 主な項目 |
|---|---|---|
| `AI_SUSPENDED` | 停止が始まった | `reason` / `suspendedSinceWorldTime` |
| `AI_SUSPENSION_REASON_CHANGED` | 停止中に理由だけが変わった（座る → リードなど）。停止は続いている | `reason` / `previousReason` / `suspendedSinceWorldTime`（停止の開始のまま） |
| `AI_RESUMED` | 再開した | `previousReason` / `suspendedSinceWorldTime` / `suspendedDurationTicks` |

理由（`reason`）はコードで区別できるものだけです：`ENTITY_NOT_LOADED` / `NOT_TAMED` / `SITTING` / `LEASHED` /
`NO_RECENT_DECISION`（どれにも当たらないのに 200 ticks = 10 秒決定がない）。飼い主が遠いだけでは止まらないので理由にしません。
各行に `lastDecisionWorldTime`（最後の決定の時刻）も付きます。

### 解析

```bash
python analysis/player_behaviour.py "<world>/aiwolf-research"
python analysis/ai_availability.py "<world>/aiwolf-research"
```

`player_behaviour.py` はセッション × フェーズごとのプレイヤーの行動の回数・1 分あたりの回数と、飼い主とオオカミの距離を、
`ai_availability.py` は停止区間と、フェーズごとの稼働時間・稼働時間あたりの決定数・行動の割合を出します（`analysis/README.md`）。

1 セッションが終わったら、まずデータの品質を検査します（Phase 25D/E）：

```bash
python analysis/check_session_integrity.py "<world>/aiwolf-research" <sessionId|latest>
```

最後に `PILOT DATA QUALITY` と `PASS` / `WARNING` / `FAIL` を出します。手順は [`HUMAN_STUDY_DRY_RUN.md`](HUMAN_STUDY_DRY_RUN.md)。

## 修正前のデータの注意：FEED が 2 倍（255a617 で修正）

255a617 より前は、餌やり 1 回で Fabric の `UseEntityCallback` が 2 回（`INTERACT_AT` と `INTERACT`）発火し、
**`events.jsonl` に FEED が同じ個体・同じ `worldTime` で 2 件**書かれていました。撫で（PET）はクールダウンがあるので影響ありません。

* 修正前のパイロットデータでは FEED の回数が 2 倍です。修正前の行は `logEnvelopeVersion` を持たないので見分けられます。
* 再発防止：Java のテスト（`PilotReadinessTest`）が 1 回の餌やりで FEED と PLAYER_FEED が 1 件ずつであることを、
  `check_session_integrity.py` が同じ個体・同じ tick の FEED / PLAYER_FEED の重複を **FAIL** として検査します。
* `python analysis/player_behaviour.py` は `events.jsonl` の FEED を検査し、`feed_check.csv` に同じ tick の重複数と、
  重複を 1 件と数え直した `feedDeduplicated` を出します。
* 回数を数え直しても、**その個体の内部状態・記憶・選好は 2 回分の餌やりを経験したあと**です。学習の結果を使う解析では、
  修正前に育てた個体は除外するか、別に扱ってください。

## 版の履歴

| ログ | 版 | 変更 |
|---|---|---|
| 決定 | 1 | 導入時（`decisionLogSchemaVersion` 項目なし。この項目がない行は 1 とみなす） |
| 決定 | 2 | 各候補に `contextSpatialMemory` / `contextEmergency` / `contextOther` / `componentSum`（Phase 14B） |
| 決定 | 3 | `parameterSetVersion` / `adaptiveFeatures` / `wolfSubjectId` / `context` / `contextFeatures` / `retrievedMemories` / `preferences`（Phase 19） |
| 出来事 | 3 | `stateBefore` / `stateDelta` / `preferenceDelta` / `createdMemoryId` |
| 出来事 | 4 | `wolfSubjectId` / `experimentSeed` / `randomSeed` / `adaptiveFeatures`（Phase 19） |
| 全行 | — | 先頭に `runId`（Phase 19。版番号とは独立。封筒の版 1） |
| 全行 | 封筒 2 | 先頭に `logEnvelopeVersion` / `sessionId` / `phaseId`（Phase 20） |
| 全行 | 封筒 3 | 先頭に `logSequence`（Phase 25B） |
| セッション | 1 | 導入時（Phase 20・24） |
| セッション | 2 | SESSION_START に `modVersion` / `buildRevision` / `buildDirty` / `protocolVersion` / `parameterSetVersion`（Phase 25A） |
| プレイヤー | 1 | 導入時（Phase 21） |
| プレイヤー | 2 | サンプルの `motion` に `hasBaseline` / `noBaselineReason`（Phase 25 P0-4） |
| AI の停止 | 1 | 導入時（Phase 22） |

決定ログの `schemaVersion` は歴史的経緯で**パラメータセットの版**（`PARAMETER_SET_VERSION`）が入っています。
スキーマの版は `decisionLogSchemaVersion` を見てください。版 3 からは `parameterSetVersion` も明示されています。

## Human Study に必要な項目の対応表

研究計画（Phase 19）で挙げた項目と、現在のログの対応です。

| 必要な項目 | 決定ログ | 出来事ログ | 備考 |
|---|---|---|---|
| runId | ✅ `runId` | ✅ `runId` | サーバ起動ごと |
| participantId / subjectId | ✅ `subjectId` | ✅ `subjectId` | 飼い主の匿名 ID（ワールドの salt で SHA-256。`subject-` + 12 桁） |
| wolfSubjectId | ✅ `wolfSubjectId` | ✅ `wolfSubjectId` | 個体の匿名 ID（`wolf-` + 12 桁）。`wolfUuid`（エンティティ UUID）も残している |
| experimentalCondition | ✅ `experimentalCondition` + `adaptiveFeatures` | ✅ 同左 | 条件 ID と、有効だった機構の中身の両方 |
| parameterSetVersion | ✅ `parameterSetVersion` | ✅ `parameterSetVersion` | |
| seed | ✅ `experimentSeed` / `randomSeed` | ✅ `experimentSeed` / `randomSeed` | `randomSeed` は個体固有のシード |
| decisionIndex | ✅ `decisionIndex` | — | 出来事は決定と独立に起きるので持たない |
| timestamp / elapsedTicks | ✅ `epochMillis` / `worldTime` | ✅ `epochMillis` / `worldTime` | `worldTime` はワールドの tick（20 tick = 1 秒） |
| context | ✅ `context`（生の値）/ `contextFeatures`（正規化後） | — | |
| stateBefore / stateAfter / stateDelta | `state`（決定時の値） | ✅ `stateBefore` / `stateAfter` / `stateDelta` | 状態は出来事でしか変わらないので、前後と差分は出来事ログにある |
| retrievedMemories | ✅ `retrievedMemories`（ID・種類・時刻・検索スコアと内訳） | `createdMemoryId` | |
| preferenceBefore / After / Delta | `preferences`（決定時の値） | ✅ `preferenceBefore` / `preferenceAfter` / `preferenceDelta` | 同上 |
| behaviorScoreBreakdown | ✅ `behaviorScores`（全候補 × 全成分 + `componentSum`） | — | Option A（全候補）。成分の定義は `BehaviorScore.components()` |
| selectedBehavior / selectedProbability / randomValue | ✅ | — | `temperature` も記録 |

### 容量の目安

決定ログ 1 行は版 3 で最大約 5.1 KB（実測：8 候補すべてが選択可能 + 検索された記憶 5 件 + runId。
候補が少ない場面ではこれより小さい）。決定は 2 秒ごとなので、1 匹 × 1 時間で約 1,800 行・最大約 9 MB。
参加者 30 人 × 1 匹 × 4 セッション × 45 分（約 16 万行）でも最大約 0.8 GB で、
全候補を残す（Option A）で問題ないと判断した。

## Phase 19 で提案した項目の対応状況

| 項目 | 状態 |
|---|---|
| sessionId | ✅ Phase 20：封筒の `sessionId` / `phaseId`、`sessions.jsonl` |
| 個体が決定できない区間（座っている・リード） | ✅ Phase 22：`ai-availability.jsonl` |
| プレイヤーの行動（Player Behavior Logger） | ✅ Phase 21：`player-events.jsonl` / `player-samples.jsonl` |
| 実験条件の割付の記録 | ✅ Phase 23：割付表の SHA-256 を `sessions.jsonl` に記録 |

**個人情報は取得しない方針は変えない**：プレイヤー名・UUID・IP・チャットの内容はどのログにも書かない。
ID はワールドの salt で匿名化した ID だけ。salt は `aiwolf.properties`（Git 管理外）にある。
