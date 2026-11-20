# Human Study の通し確認（ドライラン）手順

参加者を呼ぶ前に、実験者 1 人で **30〜45 分のセッションを 1 回通し**、ログが解析に使える形で残ることを確かめる手順です。
最後に `check_session_integrity.py` が `PILOT DATA QUALITY: PASS` を出せば合格です。

**ドライランでは結果の良し悪し（オオカミがどう振る舞ったか）を評価しません。** 確かめるのは、研究質問に必要なデータが
正しく取れるかだけです。ドライランの結果に合わせて AI のパラメータを調整しないでください。

* AI の研究パラメータ・行動・記憶・選好・状態の更新規則は、この手順では一切変えません。
* ログの項目は [`research-log-schema.md`](research-log-schema.md)、検査の閾値は [`analysis/README.md`](../analysis/README.md) を参照。
* 各ステップの「確認するログ」は、ステップの直後でなくても、最後の検査でまとめて確かめられます。
  ログは 30 秒ごとに書き出されるので、途中で見る場合は `/wolfai export` で書き出してから見てください。

> **ラベル・割付 ID に参加者を特定できる情報を入れないでください。**
> DO NOT use participant names, email addresses, student IDs, UUIDs, or other identifying information.
> Use anonymous labels such as p001-s01.
> ドライランでは研究者本人を Participant 0 として、`p000-s01` のようなラベルを使います。

## 0. 準備（起動前）

| 確認すること | 方法 |
|---|---|
| コードがコミット済み | `git status` が空。未コミットの変更があると `buildDirty=true` になり、検査が WARNING を出す |
| ビルドの版 | `./gradlew build`。ログの最初に `Build: modVersion=…, buildRevision=<commit>, buildDirty=false, protocolVersion=…` |
| 実験手順の版 | `config/aiwolf.properties` の `humanStudyProtocolVersion`（手順を変えたら上げる） |
| テスト | `./gradlew test` と `python -m unittest discover -s analysis -p "test_*.py"` がすべて成功 |

## 手順

| # | 操作 | 確認するログ |
|---|---|---|
| 1 | **新しいワールド**（または実験用に保存したワールドのコピー）を開く。`./gradlew runClient` で起動し、オオカミを 1 匹テイムする | `<world>/aiwolf-research/` ができる。サーバのログに `Research logs will be written to … (runId run-…)` |
| 2 | 割付を設定する：`/wolfai assignment set A-01`、`/wolfai assignment order state_memory>state_only`（使う場合は `/wolfai assignment table`） | `sessions.jsonl` に `study_control` / `ASSIGNMENT_SET`（`assignmentId`・`conditionOrder`）。割付表は SHA-256 の `assignmentTableHash` だけ |
| 3 | セッション開始：`/wolfai session start p000-s01` | `sessions.jsonl` の `SESSION_START` に `modVersion` / `buildRevision` / `buildDirty` / `protocolVersion` / `parameterSetVersion`、`targetSessionId`（**この sessionId を控える**） |
| 4 | `/wolfai phase set BASELINE`。約 5 分、オオカミのそばを歩く・立ち止まる | `PHASE_CHANGED`（`toPhase: BASELINE`）。以後の全ファイルの行の `phaseId` が `BASELINE`。`player-samples.jsonl` が 1 秒に 1 行 |
| 5 | `/wolfai phase set TRAINING`。**20〜30 分**（検査の下限は 10 分）、撫でる（スニーク＋素手で右クリック）・**骨をちょうど 1 回だけ与える**（体力が減っているとき）・敵と戦う・オオカミを座らせて 30 秒ほど待ってから立たせる | `player-events.jsonl` に `PLAYER_PET` / `PLAYER_FEED` / `PLAYER_ATTACK_HOSTILE` など。**骨 1 回で `events.jsonl` の FEED も `PLAYER_FEED` も 1 件**（同じ tick に 2 件あれば二重記録の再発）。座らせた間は `ai-availability.jsonl` に `AI_SUSPENDED`（`SITTING`）→ `AI_RESUMED`（`suspendedDurationTicks`） |
| 6 | TRAINING の終わりの状態を Snapshot：`/wolfai snapshot save after-training`（使う手順なら `/wolfai snapshot restore after-training` も） | `sessions.jsonl` に `SNAPSHOT_SAVED`（`wolfSubjectId`・有効な機構） |
| 7 | `/wolfai phase set MEMORY_TEST`。割付の `conditionOrder` の順に記憶の条件を切り替える（例：`/wolfai memory condition off` → 観察 → `/wolfai snapshot restore after-training` → `/wolfai memory condition on` → 観察）。2 つの条件が同じ Snapshot から始まる | `MEMORY_CONDITION_CHANGED`（`fromCondition` / `toCondition`、`memoryEnabled` など）。決定ログの `adaptiveFeatures` が切り替わる |
| 8 | `/wolfai phase set EVALUATION`。評価モード（**登録されている全研究オオカミの学習が止まる**）（`/wolfai evaluation start` → 観察 → `/wolfai evaluation stop`）で約 3 分 | `EVALUATION_STARTED` / `EVALUATION_STOPPED`。評価中の出来事では、記憶が作られず（`createdMemoryId` が null）、選好と長期の内部状態が変わらない（一時的な感情は反応する） |
| 9 | セッション終了：`/wolfai session stop`。そのあとワールドを保存して閉じる（ログが書き出される） | `SESSION_STOP`（`reason: COMMAND`、`durationTicks`）。サーバを閉じるまでセッションを止め忘れると `reason: SERVER_STOPPING`（検査が WARNING） |
| 10 | データ品質の検査（下） | 最後に `PILOT DATA QUALITY: PASS` |
| 11 | `python analysis/player_behaviour.py "<world>/aiwolf-research"` | `player_event_rates.csv` で TRAINING の PET・FEED の回数が実際の操作と一致。`feed_check.csv` の `sameTickDuplicates` が 0 |
| 12 | `python analysis/ai_availability.py "<world>/aiwolf-research"` | `ai_suspensions.csv` に手順 5 の SITTING が 1 件（長さが実際に座らせた時間と一致）。`ai_availability_by_phase.csv` の `decisionsPerAvailableMinute` が約 30 |

## 10. データ品質の検査

```bash
python analysis/check_session_integrity.py "<world>/aiwolf-research" latest --forbidden-player-name Researcher
```

`latest` の代わりに手順 3 で控えた sessionId を渡してもかまいません。開発環境のプレイヤー名は `build.gradle` で `Researcher` に固定されています。
UUID も分かっていれば `--forbidden-player-uuid <uuid>` で渡します（`/wolfai` のログやサーバのログで確認できますが、**この値をどこかに書き残さないでください**）。

出力の見方：

| セクション | 確認すること |
|---|---|
| セッション | START と STOP が 1 件ずつ、長さが実際の時間と合う（30〜45 分） |
| 版 | `buildRevision` が `git rev-parse --short HEAD` と一致、`buildDirty: false`、`protocolVersion` が想定どおり |
| フェーズ | BASELINE → TRAINING → MEMORY_TEST → EVALUATION の順で、長さが手順どおり |
| ログ | 各ファイルの件数が 0 でない（`damage` / `signatures` は 0 でもよい） |
| logSequence | 重複なし。欠番は `demo/` を使った場合などにありうる |
| オオカミの決定と AI の稼働 | active ratio が手順 5 で座らせた時間の分だけ下がっている。per active minute が約 30 |
| プレイヤーのサンプリング | coverage ≥ 95 %、max gap < 10 秒。各セッションの最初のサンプルは `hasBaseline: false`（`noBaselineReason`）で移動量なし |
| プレイヤーの行動 | 手順 5 で行った操作の回数と一致（とくに PLAYER_FEED が骨を与えた回数と同じ） |
| 個人情報 | 「見つからなかった」 |

**WARNING の場合**：「判定の理由」を読み、想定どおりの理由（例：わざと短くした）なら使ってよい。そうでなければ原因を調べる。
**FAIL の場合**：そのセッションのデータは解析に使わない。原因を直してから取り直す。

## ドライランで確認すること（まとめ）

| 観点 | どこで |
|---|---|
| セッション・フェーズの境界 | 検査の「セッション」「フェーズ」 |
| 版（コード・手順・パラメータ） | 検査の「版」、サーバのログの `Build:` 行と `runId` |
| プレイヤーのログ・オオカミのログ | 検査の「ログ」「プレイヤーの行動」「オオカミの決定と AI の稼働」 |
| 記憶の条件・Snapshot・評価モード | `sessions.jsonl` の `MEMORY_CONDITION_CHANGED` / `SNAPSHOT_*` / `EVALUATION_*` |
| AI の停止 | 検査の active ratio と suspension reasons、`ai_availability.py` |
| 書き込みの順序 | 検査の「logSequence」（重複・逆行なし） |
| 個人情報 | 検査の「個人情報」 |
| サンプリング | 検査の「プレイヤーのサンプリング」 |
| データ量 | `aiwolf-research/` の大きさ（決定ログは 1 匹 1 時間で最大約 9 MB） |
| 解析スクリプト | 手順 11・12 がエラーなく CSV を出す |

## 既知の制約

* **評価モード（学習の凍結）は、登録されている全研究オオカミにかかります**（個体ごとではありません）。
  参加者 1 人・研究オオカミ 1 匹のパイロットでは問題になりません。複数の個体を同時に使う手順にする場合は注意してください。
* Snapshot と評価モードは、Demo Mode 中か実験セッション中にだけ使えます。

## 終わったら

* ドライランのワールドは本実験のワールドと混ぜない（別のフォルダに保存しておく）。
* 検査の出力（`check_session_integrity.py` の標準出力）を実験ノートに貼る。参加者の情報は含まれない。
* 手順を変えた場合は `humanStudyProtocolVersion` を上げ、このファイルを更新する。
