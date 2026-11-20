# 人を対象にした実験（Study 2 / Study 3）への準備状況（2026-11-14 時点）

このメモは、オフライン実験（Phase 14〜18）の結果を踏まえて、参加者を使う実験へ進む前に
**何がすでにあり、何が足りないか**をまとめたものです。設計案を含みますが、**実装したのは「既にあるもの」の列だけ**です。

## 1. 予定している研究

| 研究 | 問い | 必要な仕組み |
|---|---|---|
| Study 1（現在） | 経験の違いが行動の個体差を生むか（システムの性質） | 育成・評価・アブレーション（オフラインで済む部分は完了） |
| Study 2 | AI ペットが経験を「覚えている」と、人は本当に感じるか | 記憶 ON / OFF の個体を同じ経験で育て、参加者に見せて評価してもらう |
| Study 3 | 人と AI ペットは互いに変化するか | AI 側のログに加え、**プレイヤー側の行動ログ**が必要 |

## 2. 既にある仕組みと、Study 2 / 3 での使い道

| 仕組み | 実装 | Study 2 での使い道 | Study 3 での使い道 |
|---|---|---|---|
| アブレーション条件（`AdaptiveFeatures`） | ✅ 7 条件（`/wolfai mode`） | 記憶 ON（STATE_MEMORY / FULL）と OFF（STATE_ONLY / STATE_PREFERENCE）を比べる | 条件ごとにプレイヤーの行動が変わるかを見る |
| Snapshot / Restore | ✅ `/wolfai snapshot`（Demo Mode 中か実験セッション中） | 同じ経験を持つ個体を参加者ごとに同じ状態から見せる | 介入前後の状態を保存 |
| Evaluation Freeze | ✅ `/wolfai evaluation`（Demo Mode 中か実験セッション中。登録されている全研究オオカミにかかる） | 参加者が見ている間に個体が学習して変わらないようにする | 「学習させない期間」を作る対照 |
| スコア分解ログ | ✅ 決定ログ版 3 | 参加者が「覚えている」と感じた場面で、実際に記憶の成分が効いていたかを照合 | 行動の変化の原因（状態・記憶・選好）を分ける |
| 匿名 ID | ✅ `subjectId` / `wolfSubjectId` / `runId` | 参加者の回答とログの突き合わせ | 同左 |
| 個体性評価 | ✅ `./gradlew individuality` | 刺激として使う個体の差が十分かを事前確認 | — |

## 3. 人を使う前に必要な修正（優先度順）

### 必須

1. **内部状態の天井効果への対処を決める**（指導教員と相談）。
   ふつうの飼い主中心の育成で attachment は約 15 分、trust は約 24 分で上限に張り付く
   （[`lab-notes/2026-11-14_state-saturation.md`](lab-notes/2026-11-14_state-saturation.md)）。
   30〜45 分 × 複数セッションの計画では、2 セッション目以降、内部状態が経験を反映しなくなる。
   対処する場合はパラメータの変更なので `PARAMETER_SET_VERSION` を上げ、本ノートの測定（`./gradlew saturation`）を取り直す。
2. **energy の下限張り付きを実機で確認する**。探索中心・危険の多い育成で energy が 0 に張り付き、休息が選ばれない
   （同ノート「予想していなかった発見」）。実機のパイロットで REST_NEAR_PLAYER の割合を確認する。
3. **出来事の頻度を実測する**。オフライン実験の頻度は仮定。パイロットの `events.jsonl` から 1 分あたりの回数を数え、
   `StateSaturationSimulation.rates` の typical を置き換えて再計算する。
4. **ゲーム内での動作確認**。Demo Mode・Overlay・Snapshot・Evaluation・新しいアブレーション条件・ログ版 3 は、
   単体テストとオフラインでは確認したが、Minecraft を起動しての確認はまだ（`./gradlew runClient`）。
   Human Study のログ（Phase 20〜25）は [`HUMAN_STUDY_DRY_RUN.md`](HUMAN_STUDY_DRY_RUN.md) の手順で 30〜45 分を通し、
   `analysis/check_session_integrity.py` が `PILOT DATA QUALITY` / `PASS` を出すことを確認する。
5. **倫理審査と同意書**。匿名 ID の方式（salt + SHA-256）、記録する項目（`research-log-schema.md`）、
   保存期間、Demo Mode のデバッグ表示を参加者に見せないこと（交絡）を申請書に書く。

### Study 2 に必要

6. **刺激の作り方を決める**。記憶 ON / OFF の個体を「同じ経験」で育てる手順。Snapshot は個体ごとなので、
   2 条件の個体を同じ出来事列で育てるには、実験者が同じ手順を 2 回行うか、オフラインの育成（`StateSaturationSimulation.rear`）の結果を
   ワールドの個体へ読み込む仕組み（**未実装**：NBT への書き出し）が要る。
7. **場所・出来事に特有の評価場面を作る**。アブレーションの結果、記憶の効果は全体の行動分布より
   「危険だった場所での回避」のような場面に現れる（[`lab-notes/2026-11-14_ablation-individuality.md`](lab-notes/2026-11-14_ablation-individuality.md)）。
   参加者に見せる場面はここに置くべき。

### Study 3 に必要

8. ~~**Player Behavior Logger**~~ → ✅ Phase 21 で実装（`player-events.jsonl` / `player-samples.jsonl`。座標・名前・UUID は書かない）。
   解析は `analysis/player_behaviour.py`。
9. ~~**sessionId**~~ → ✅ Phase 20 で実装（全行の封筒に `sessionId` / `phaseId`、`sessions.jsonl`）。
   あわせて AI の停止区間（Phase 22、`analysis/ai_availability.py`）・割付の記録（Phase 23）・記憶 ON / OFF の切り替え（Phase 24）も入った。
   項目は [`research-log-schema.md`](research-log-schema.md) を参照。

## 4. この時点で答えられる問い

| 問い | 答え | 根拠 |
|---|---|---|
| Q1：内部状態はどのくらい早く飽和するか | typical の飼い主中心で attachment 14.5 分・trust 23.5 分（30 シードの中央値）で 0.99 | state-saturation ノート |
| Q2：行動変化はどの成分から来るか | 交流の最初の数分はエピソード記憶（62 %）、数分後からは内部状態（59 %、主に attachment）。選好は 5〜8 % | score-breakdown ノート |
| Q3：記憶・選好は個体性に必要か | 個体性の約 73 % は内部状態だけで生じる。記憶（+0.88）と選好（+0.60）は有意に上乗せし、FULL が最大（+1.85、分離比で）。記憶の効果は場所に特有 | ablation-individuality ノート |

いずれもオフラインの数値実験で、出来事の頻度は仮定である。人を使う前に、パイロットで仮定を実測値に置き換えること。
