# AI Wolf Research Mod

Minecraft (Fabric) 用の研究 MOD。オオカミの行動傾向が **プレイヤーとの共有経験によって形成される** 過程を扱い、
人間がその個体固有の「らしさ」を行動から認識できるかを調べるための実験基盤です。

> **Personality is not stored. Personality is observed.**

> [!NOTE]
> このプロジェクトは**趣味として個人的に作っているもの**です。
> 作りながら、ゆくゆくは研究（AI ペットの記憶を人が行動から感じ取れるか、など）にもつなげていきたいと考えています。
> 研究計画や実験手順のドキュメントは検討中の内容を含み、確定したものではありません。
>
> *This is a hobby project that I hope to develop into research over time.
> Planning documents in `docs/` are work in progress.*

---

## 1. 研究目的

固定された性格ラベル（BRAVE / SHY / FRIENDLY など）を **一切持たせない**。
すべての個体は同一の初期パラメータ・同一のアルゴリズム・同一の行動集合から開始し、

```
経験 → 内部状態変化 → エピソード記憶 → 行動傾向変化 → 次の行動
```

という循環の反復によって、結果として「この個体は慎重そう」「この個体はついてくる」と
人間が感じる状態を作り出します。

### 検証する仮説

| | 内容 |
|---|---|
| H1 | 異なるインタラクション履歴を経験した AI ペットは、同一アルゴリズム・同一初期状態であっても異なる Behavioral Signature を形成する |
| H2 | 同一 AI ペットの Behavioral Signature は評価セッション間で一定の一貫性を示す |
| H3 | Adaptive 条件で育成したユーザーは Static 条件より高い確率で自分のペットを識別できる |
| H4 | ユーザーの個体性評価は、個体間の Behavioral Signature の差と関連する |

H1 はコードレベルでも `EmergentIndividualityTest` により検証しています。

---

## 2. システム構成

```
Minecraft World
      │
      ▼
Event Detection / ContextBuilder    ← 未実装（Minecraft 依存）
      │
      ▼
WolfEvent / WolfContextSnapshot     ★ Minecraft 型を含まない純データ
      │
      ▼
┌──────────────── WolfAiAgent（薄いオーケストレータ）────────────────┐
│     │                                                             │
│     ├──────────────┬──────────────┬───────────────┐               │
│     ▼              ▼              ▼               ▼               │
│ StateUpdater   MemoryStore   SpatialMemory   PreferenceVector     │
│     │              │              │               │               │
│     │              ▼              │               │               │
│     │       MemoryRetriever       │               │               │
│     │        (Top-K 検索)         │               │               │
│     │              │              │               │               │
│     │              ▼              │               │               │
│     │    MemoryImpactCalculator   │               │               │
│     │              │              │               │               │
│     └──────┬───────┴──────────────┴───────────────┘               │
│            ▼                                                      │
│      BehaviorScorer   ── behavior scores（成分の内訳つき）         │
│            │                                                      │
│            ▼                                                      │
│      PolicySelector (Softmax + 決定論的乱数)                       │
│            │                                                      │
└────────────┼──────────────────────────────────────────────────────┘
             ▼
      Selected Behavior
             │
             ▼
     Behavior Executor              ← 未実装（Minecraft 依存）
             │
             ▼
        Outcome / Event ────────────→ 次の経験へ
```

★ の枠内はすべて Minecraft 非依存。Minecraft 側の責務は
「`WolfEvent` を作って `WolfAiAgent.onEvent` を呼ぶ」「`WolfContextSnapshot` を作って
`WolfAiAgent.decide` を呼び、返ってきた行動を実行する」の 2 点だけである。

### 設計上の最重要原則

* **BehaviorScorer / PolicySelector は Minecraft API から完全に独立している。**
  `WolfEntity` / `ServerWorld` / `BlockPos` を参照せず、引数は
  `WolfInternalState` / `WolfContextSnapshot` / `MemorySummary` / `PreferenceVector` のみ。
  そのため Minecraft を起動せずに数万回のシミュレーションを実行できる。
* **個体差を直接作る分岐は存在しない。** `if (wolf == A) brave = true;` のようなコードは禁止。
  重み表・影響行列・初期値はすべての個体で共通。
* **LLM を毎 tick 呼ばない。** MVP では外部 AI API を使用しない。将来的に LLM を接続する場合も、
  `MemorySummary` のような疎結合な境界を経由する。

### パッケージ構成

| package | 責務 | Minecraft 依存 |
|---|---|---|
| `core` | 研究パラメータ、数値ユーティリティ、実験条件、JSON 出力 | なし |
| `state` | 内部状態モデル、状態分類、状態影響行列、StateUpdater、個体レコード | なし |
| `event` | 出来事の種別（valence / arousal / salience）と発生記録 | なし |
| `context` | 文脈スナップショットと文脈特徴量 | なし |
| `memory` | エピソード記憶、保管庫、Top-K 検索、影響行列、空間記憶 | なし |
| `learning` | 学習済み行動選好ベクトル、イベント影響行列 | なし |
| `behavior` | 行動種別、重み表、スコアラ、実行可能判定、Softmax 選択 | なし |
| `random` | 決定論的乱数供給器 | なし |
| `agent` | `WolfAiAgent`（上記をまとめる薄いランタイム） | なし |
| `simulation` | 標準テストバッテリ、Behavioral Signature、実験前シミュレーション | なし |
| `log` | 研究ログの直列化と JSONL 書き出し | 一部あり |
| `export` | 研究データのエクスポート形式 | なし |
| `persistence` | NBT 変換とワールドセーブデータへの保存 | **あり** |
| `mc` | ゲームとの橋渡し（文脈構築・行動実行・ゴール・個体登録・デバッグ表示） | **あり** |
| `command` | `/wolfai` コマンド群 | **あり** |
| `mixin` | `MobEntity.goalSelector` のアクセサと、被弾の観測（いずれも読むだけ） | **あり** |
| （ルート） | `AiWolfMod`（Fabric エントリポイント、配線のみ） | **あり** |

Minecraft に依存するのは下 5 つだけで、41 クラスは Minecraft を起動せずにコンパイル・テストできます。

---

## 3. 内部状態一覧

すべて `double`、範囲 `0.0 〜 1.0`、更新時は必ず clamp されます。**初期値は全個体同一**です。

| キー | 分類 | 初期値 | ベースライン | 意味 |
|---|---|---|---|---|
| `trust` | Relational | 0.50 | — | 飼い主への信頼 |
| `attachment` | Relational | 0.50 | — | 飼い主への愛着 |
| `fear` | Emotional | 0.30 | 0.30 | 恐怖感受性 |
| `stress` | Emotional | 0.20 | 0.20 | ストレス |
| `curiosity` | Motivational | 0.50 | — | 好奇心 |
| `autonomy` | Motivational | 0.50 | — | 自律性 |
| `energy` | Motivational | 1.00 | — | 活力 |

* **Relational**：飼い主との経験によってのみ変化し、時間経過では減衰しない。
* **Emotional**：イベントで急変し、`x ← x + rate × (baseline − x)` でベースラインへ回帰する。
* **Motivational**：行動（探索・休息）およびイベントによって変化する。

スコア計算では `z = 2x − 1` により `[-1, +1]` へ変換し、0.5 を中立として扱います。

---

## 4. 行動一覧

### Macro Behavior（研究で分析する行動 / 8 種）

`FOLLOW_CLOSE` `FOLLOW_NORMAL` `EXPLORE_NEARBY` `APPROACH_PLAYER`
`DEFEND_PLAYER` `RETREAT` `AVOID_LOCATION` `REST_NEAR_PLAYER`

### Expression Behavior（表現層 / スコアリング対象外）

`LOOK_AT_PLAYER` `LOOK_AWAY` `HEAD_TILT` `SHORT_PAUSE` `TAIL_MOVEMENT` `SMALL_APPROACH`

表現層を分離することで、研究ログ上の行動分布（「FOLLOW_CLOSE が 30%、EXPLORE が 40%」）が
見た目の演出によって汚れません。

---

## 5. 中核アルゴリズム

### 5.1 行動 preference

```
H_t(a) = β_a
       + W_state,a  · z(S_t)          z(x) = 2x − 1
       + W_context,a · C_t            C_t ∈ [0,1]
       + M_t(a)                       記憶由来の寄与
       + ρ · P_t(a)                   学習済み選好
       + κ · I(a = a_{t−1})           継続ボーナス
       − penalty(a)
```

### 5.2 行動選択（Softmax）

```
π(a|t) = exp(H_t(a)/τ) / Σ_a' exp(H_t(a')/τ)
```

数値安定化のため `exp((H − H_max)/τ)` を使用します（`MathSupport.softmax`）。

### 5.3 選好の更新

```
P_{t+1}(a) = clip( (1−λ)·P_t(a) + η · salience(e_t) · impact(e_t, a),  −1, +1 )
```

`η = 0.03`、`λ = 0.01` と十分小さく設定しており、**1 回の出来事で行動傾向が激変しません**。

### 5.4 緊急時の扱い

HP が 15% を下回ると `EMERGENCY_LOW_HEALTH` 特徴量が 1 になり、`RETREAT` へ +5.0 が加算されます。
`if` 分岐で行動を強制するのではなくスコア構造に組み込むことで、研究ログの解析形式が一貫します。

### 5.5 初期 Weight（全個体共通・`BehaviorWeights`）

| Behavior | 主な正の要因 | 主な負の要因 |
|---|---|---|
| FOLLOW_CLOSE | attachment +0.9 / fear +0.7 / stress +0.4 / threat +0.5 | autonomy −0.7 |
| FOLLOW_NORMAL | base +0.2 / trust +0.4 / attachment +0.3 / ownerMoving +0.3 | fear −0.1 |
| EXPLORE_NEARBY | curiosity +1.0 / autonomy +0.8 / energy +0.4 | fear −0.8 / stress −0.7 / threat −0.6 / locationFear −0.4 |
| APPROACH_PLAYER | attachment +0.9 / trust +0.4 / ownerDistanceFar +0.6 | autonomy −0.5 / nearOwner −0.4 |
| DEFEND_PLAYER | trust +0.7 / attachment +0.5 / energy +0.4 / ownerUnderAttack +1.0 / threat +0.8 | fear −0.5 |
| RETREAT | fear +1.1 / stress +0.8 / wolfHealthLow +1.2 / emergency +5.0 | energy −0.2 |
| AVOID_LOCATION | locationFear +1.3 / fear +0.5 | curiosity −0.3 |
| REST_NEAR_PLAYER | lowEnergy（energy −1.0）/ attachment +0.4 / stress +0.3 | threat −1.0 |

> これらは「性格」ではなく、**現在の内部状態と文脈がどの行動を誘発するか** を定義する写像です。

---

## 5.6 エピソード記憶

### 出来事（`WolfEventType`）

各出来事は valence（快・不快の向き）と arousal（強度）を持ち、そこから salience が決まる。

```
salience = 0.5 * |valence| + 0.5 * arousal + bonus     （[0,1] へ clamp）
```

| 出来事 | valence | arousal | salience |
|---|---:|---:|---:|
| `pet` | +0.5 | 0.2 | 0.35 |
| `feed` | +0.4 | 0.2 | 0.30 |
| `player_protected_wolf` | +0.9 | 0.7 | 0.90 |
| `defend_success` | +0.7 | 0.7 | 0.75 |
| `reunion` | +0.6 | 0.5 | 0.60 |
| `safe_solo_exploration` | +0.4 | 0.3 | 0.35 |
| `new_biome` | +0.3 | 0.4 | 0.35 |
| `sleep_near_player` | +0.3 | 0.1 | 0.20 |
| `player_and_wolf_combat` | +0.2 | 0.7 | 0.45 |
| `monster_encounter` | −0.3 | 0.5 | 0.40 |
| `long_separation` | −0.4 | 0.3 | 0.35 |
| `player_damaged_near_wolf` | −0.5 | 0.6 | 0.55 |
| `wolf_damaged_during_exploration` | −0.6 | 0.7 | 0.65 |
| `wolf_damaged` | −0.7 | 0.6 | 0.65 |
| `wolf_low_health` | −0.8 | 0.8 | 0.85 |
| `near_death` | −1.0 | 0.9 | 1.00 |

### 戦闘イベントの成立条件

`player_protected_wolf` / `defend_success` / `player_and_wolf_combat` は
学習済み選好へ強く入るため、誤検出はそのまま研究結果の誤りになる。
そこで「攻撃対象が変わったら戦闘が終わった」という判定はやめ、
**相手ごとに戦闘エピソード（`CombatEpisode`）を開いて**追跡する。

| 出来事 | 成立条件 |
|---|---|
| `player_protected_wolf` | オオカミがこの敵から被弾し、**その後に**飼い主がこの敵を少なくとも一度攻撃し、敵が撃破され、オオカミが生存 |
| `defend_success` | 飼い主がこの敵から被弾しており、オオカミがこの敵へ介入し、敵が撃破され、オオカミが生存 |
| `player_and_wolf_combat` | 双方が関与して敵が消えたが、上のどちらにも当てはまらない |

### ダメージの捉え方と、研究上の定義

**研究上のダメージは「実際に失われた耐久」です。**

```
damageDefinition = EFFECTIVE_HEALTH_LOSS

actualDamage = (health + absorption)適用前 − (health + absorption)適用後
```

攻撃の威力ではありません。防具・エンチャント・Resistance による軽減をすべて反映した後の、
実際に減った量です。研究上の関心は「どれだけ強く攻撃されたか」ではなく「どれだけ傷ついたか」
だからで、同じゾンビの一撃でも防具を着けたオオカミとそうでないオオカミでは経験の重さが違います。

Absorption（金のリンゴの黄色いハート）を含めるのは、HP が減らなくても傷つけられた事実は
起きているためです。ログには `healthBefore/After` と `absorptionBefore/After` を**別々に生で残す**ので、
解析時に「HP のみの損失」へ組み替えることもできます（`healthLoss` 列も併記しています）。

#### なぜ Fabric API だけでは足りないのか

`ServerLivingEntityEvents` の damage 系イベントは、いずれもこの値を与えてくれません。

| イベント | 問題 |
|---|---|
| `ALLOW_DAMAGE` | 「防具やその他の軽減が適用される前」に発火する。威力しか分からない |
| `AFTER_DAMAGE` | `damageTaken` は公式 Javadoc に「**防具とエンチャントによる軽減は含まない**」と明記。盾と凍結だけが反映された中間値 |
| `AFTER_DAMAGE` | さらに「**その damage で entity が死亡した場合は発火しない**」。致死攻撃の帰属が丸ごと落ちる |
| どちらも | Absorption の増減を報告しない |

そのため観測専用の Mixin（`LivingEntityDamageMixin`）を 1 つ置いています。`damage` の入口で
HP と Absorption を控え、出口で読み直すだけで、**ダメージ処理そのものは一切変更しません**
（戻り値を書き換えず、キャンセルもせず、フィールドも触りません）。Fabric 自身が `@At("TAIL")` を
使うのに対しこちらは `@At("RETURN")` を使うため、死亡して途中で返る経路も捉えられます。

#### 扱いの一覧

| 項目 | 扱い |
|---|---|
| 帰属先 | `DamageSource` の「原因となった主体」。矢は矢ではなく**射手**へ帰属する |
| ダメージ量 | `EFFECTIVE_HEALTH_LOSS`（上記） |
| 完全に防いだ攻撃 | 被害 0 として記録しない（測るのは攻撃されたことではなく受けた被害） |
| 致死攻撃 | 捕捉し、`fatal=true` と加害者・戦闘 ID を残す |
| 落下・溶岩・炎など | 内部状態は更新するが**戦闘記録は作らない**（相手のいない戦闘は結末が来ない）。`attackerType=environment` |
| 同一区間の複数の相手 | **相手ごとに分けて**蓄積する。Zombie の 2 と Skeleton の 3 を「Skeleton から 5」にしない |

**研究イベントの発火頻度は従来どおり決定ごとに最大 1 件です。** 1 発ごとに発火させると、
1 回の戦闘で 3 回噛まれた個体が 3 倍の速さで学習することになり、パイロット時と学習速度が変わります。
帰属の正確さ（相手ごと・1 発ごと）と発火頻度（決定ごとに 1 件）を分けています。

複数の相手から受けた区間で、どの相手を代表として記録するかは次の順で決めます。
**この順序は完全に決定論的で、内部のマップの走査順に依存しません**（依存させると同じ状況でも
実行のたびに帰属先が変わり、再現性が失われます）。

1. その区間に与えた合計ダメージが大きい相手（実際にいちばん痛かった経験を代表とする）
2. 同点なら、最後に攻撃してきた時刻が遅い相手（直近の脅威を優先する）
3. それも同点なら、識別子の辞書順で小さい相手（順序を一意に決めるためだけの規則）

一方、傷の重さ（瀕死 / 低 HP / 通常）の判定には**区間内で最も低かった HP 比率**を使います。
誰に殴られたかとは無関係に、その区間でどこまで追い込まれたかが重さだからです。

選択規則が効いた場面は研究ログから検証できます。被弾イベントの `metadata` に
`damageTotal`（区間合計）、`damageAttributed`（代表に帰属した量）、`damageSourceCount`（相手の数）、
`damageOrigin`（`hostile` / `environment`）、`hostileId` / `hostileType` が入ります。

### 戦闘の結末を誰に伝えるか

敵の死亡と飼い主の介入は、**その敵との戦闘を追跡している個体**へ伝えます（敵 UUID と戦闘記録の対応）。
死亡地点の近くにいる個体を探す方式では、`RETREAT` や `AVOID_LOCATION` で敵から離れた個体に
結末が届かず、その戦闘は時間切れで消えます。**消えるのが「逃げた個体の戦闘」に偏る**ため、
恐怖傾向の強い個体ほど守られた経験が記録されないという、測ろうとしている個体差に
相関した欠測が生じます。

例外は `PLAYER_DAMAGED_NEAR_WOLF` で、これは「オオカミのそばで起きた」ことが定義に含まれるため
距離で絞ります。半径は在否判定と同じ `OWNER_PRESENCE_RADIUS` です。

戦闘は次のいずれかで始まります（`combatTrigger` として研究ログに残ります）。

| きっかけ | 内容 |
|---|---|
| `wolf_damaged` | オオカミが敵から被弾した |
| `owner_damaged` | 飼い主が敵から被弾した |
| `wolf_targeted_hostile` | オオカミが敵を攻撃対象にした |

**飼い主の被弾から戦闘を開始できることが `defend_success` の前提です。** 自然な Minecraft の
戦闘は「敵が飼い主を殴る → 飼い主が被弾 → オオカミが介入」の順に進みます。飼い主の被弾時点で
戦闘を開かないと、その時点のダメージ記録が捨てられ、あとからオオカミが介入して敵を倒しても
「飼い主が攻撃を受けていた」という条件が満たされず、防衛成功が一度も成立しません。

* 飼い主が近くにいただけでは救出として数えない（攻撃の事実と**順序**を要求する）。
* 飼い主の介入は**近接でも遠距離でも同じ**。弓・クロスボウで敵を削り、オオカミがとどめを
  刺した戦闘も救出として成立する。介入は「最後の一撃を入れたか」ではなく
  「その敵に実際にダメージを与えたか」で判定する。
* **救援したあとにオオカミが同じ敵から再び被弾しても、救出の成立は取り消されない。**
  判定は時刻の比較ではなく「この戦闘で救援に入った」という事実として持つ。
  時刻で比べると、被弾 → 救援 → *再被弾* → 撃破という流れで成立しなくなり、
  救援後も攻撃が続いた戦闘——つまり劣勢で危機的だった戦闘——ほど記録から落ちる。
* オオカミが介入していない戦闘は防衛成功として数えない（飼い主が自分で倒しただけ）。
* 戦闘 ID (`combatId`) はセッションを跨いで一意。採番カウンタを永続化しているため、
  再起動後の最初の戦闘が以前の ID と衝突しない。
* 1 つのエピソードからは優先順位つきで **1 件だけ**発火する。
  同じ戦闘から複数出すと state・記憶・選好の 3 経路すべてに重複して入り、1 回の経験から過剰に学習する。
* オオカミが死亡した戦闘、敵が逃走・デスポーンした戦闘は成功として数えない。
* 無活動が 200 tick 続いたエピソードは未成立のまま回収する（開いたままにしない）。
* 発火したイベントには `metadata.combatId` が付く。どの戦闘から何件出たかを解析側で検証できる。

### 保持と剪定

1 個体あたり最大 200 件。超過時は保持スコアが最も低い記憶から削除する。

```
RetentionScore = 0.7 * Salience + 0.3 * Recency
Recency        = exp(-dt / tau)      tau = 36,000 ticks（30 Minecraft 分）
```

salience を recency より重く見るため、「瀕死になった」ような重要な記憶が
直近の些細な記憶に押し出されて消えることはない。

### 検索（Top-K）

200 件すべてを行動計算へ入れず、上位 5 件だけを使う。

```
RetrievalScore = 0.25 * Recency + 0.35 * Salience + 0.40 * Relevance
Relevance      = 0.50 * eventMatch + 0.30 * regionMatch + 0.20 * actorMatch
```

**LLM の埋め込みは使わない。** 再現性のため relevance は完全にルールベースで計算する。
region は完全一致で 1.0、隣接 region（チェビシェフ距離 1、同一ディメンション）で 0.5。

### 行動への寄与

```
MemoryEffect(b) = clip( Σ_i RetrievalScore_i × MemoryImpact(type_i, b) × |valence_i| , −1, +1 )
```

`|valence|` を掛けることで、感情的に薄い記憶が強い記憶と同じ重みで効くことを防いでいる。

---

## 5.7 空間記憶

正確なブロック座標ではなく、**24 ブロック単位の region へ量子化**して保持する。
1 ブロックずれただけで「同じ洞窟」と認識できなくなるのを避けるためである。

region ごとに `fearValue` / `positiveValue` / `visitCount` を持ち、

```
effectiveFear = clamp01( fearValue − 0.5 × positiveValue )
```

を `ContextFeature.LOCATION_FEAR` として行動スコアへ供給する。
更新は指数移動平均（学習率 0.30 × arousal）なので、1 回の出来事で場所の評価が振り切れない。
保持上限は 128 region で、超過時は重要度の低い region から削除する。

`AVOID_LOCATION` は「その場で後ろを向く」のではなく、**周囲 3×3 region のうち最も恐怖値の高い
region の中心から離れる向き**へ移動する。恐れているのは場所であって向きではないので、
自分の向きを基準に逃げると、恐れている領域の奥へ進んでしまうことがある。
逃走先は 0°・±30°・±60° の候補を順に試し、実際に恐怖領域の中心から遠ざかる地点だけを採用する
（どれも取れなければ従来の後退にフォールバックする）。

---

## 5.7.1 「飼い主が近くにいる」の定義

文脈生成・出来事検出・分離判定のすべてが **1 つの定義**（`OwnerPresence`）を使います。

```
近傍 = 飼い主が存在し、生存し、同じディメンションにいて、距離 ≤ OWNER_PRESENCE_RADIUS (48 blocks)
```

境界はちょうど 48 block を近傍に含みます。同じ「近くにいる」という概念に複数の閾値を作ると、
「飼い主のそばで被弾した」のに「長期分離中」という矛盾した記録が生まれます。

これに依存する判定：

* `PLAYER_DAMAGED_NEAR_WOLF` — 飼い主が近傍にいるときだけ成立
* `LONG_SEPARATION` — 近傍にいない状態が `LONG_SEPARATION_TICKS`（2400 ticks ≒ 2 分）続いたら 1 回だけ
* `REUNION` — 長期分離が成立したあと、近傍へ戻ったときだけ 1 回

> Phase 13 までは出来事検出が「同じワールドにいるか」しか見ていませんでした。
> 数百 block 離れていても飼い主の被弾が近接扱いになり、逆に長期分離は
> ネザーへ行くなどディメンションを変えない限り一度も成立しませんでした。

---

## 5.8 時間の扱い（シミュレーションと実機の整合）

感情のベースライン回帰と energy の増減は時間依存です。これを
**呼び出し回数ではなく経過 tick で数える**ことが、シミュレーションと実機を揃える鍵になります。

| | 決定の間隔 | 1 回の決定で進む時間 |
|---|---|---|
| 事前シミュレーション | 1 イベントごと | 600 ticks |
| Minecraft 実機 | `DECISION_INTERVAL_TICKS` | 40 ticks |

「何回呼ばれたか」で数えると時間の進み方が 15 倍ずれます。
`WolfAiAgent.advanceTime(elapsedTicks)` が経過 tick を
`MAINTENANCE_INTERVAL_TICKS`（600）で割った回数だけ更新し、余りを次回へ持ち越すので、
**経過 tick が同じなら両者で内部状態の時間変化が一致します**。端数も永続化します。

ワールド時刻が大きく飛んだ場合（`/time set`、長時間の放置）は
`MAX_MAINTENANCE_STEPS_PER_CALL`（64）で更新回数を打ち切ります。
打ち切られた個体の感情はベースラインへ近づくだけで、値が壊れることはありません。

> Phase 13 までは実機で一度もメンテナンスが呼ばれていませんでした。
> fear と stress がベースラインへ戻らず energy も動かないため、
> シミュレーションで見たパラメータの挙動が実機にまったく当てはまらない状態でした。

---

## 6. 再現性

各個体は独自の決定論的乱数供給器を持ちます。

```
seed = mix64(experimentSeed, wolfUUID)
r_k  = toUnitInterval( mix64(seed ⊕ mix64(k)) )      k = decisionIndex
```

乱数値が `(seed, decisionIndex)` の**純粋関数**なので、永続化すべき状態は `decisionIndex` だけであり、
セーブ／ロードを跨いでも「同一イベント列 + 同一シード ⇒ 完全に同一の行動列」が保証されます。
研究ログに記録した `randomSeed` と `decisionIndex` から、後日まったく同じ乱数値を再計算できます。

さらに、超越関数には `Math` ではなく **`StrictMath`** を使っています。`Math.exp` / `Math.pow` は
JVM 実装や CPU によって最終桁が 1 ULP ずれることが許されており、Softmax の確率がわずかに変わるだけで
乱数値との比較が逆転し、選ばれる行動が変わりえます。`StrictMath` は全プラットフォームでビット単位に
同一の結果を返すため、**OS やマシンを跨いでも同じ行動列が再現**されます。
別環境で研究データを再解析・再現する場面で効いてきます。

### セーブ／ロードを跨いだ連続性

再起動を挟むと行動列が変わるなら、観測された個体差を「相互作用履歴」に帰属できません。
そこで次の項目をすべて永続化します。**どれか 1 つでも落とすと再起動直後の行動が変わります。**

| 項目 | 落とすと起きること |
|---|---|
| `decisionIndex` | 乱数列の位置が巻き戻り、以降の行動列が別物になる |
| `currentBehavior` | 継続ボーナス（+0.25）が消え、再起動直後の 1 手が変わる |
| `lastDecisionTick` / `behaviourStartTick` | 決定周期がずれ、再起動直後だけ判断が早まる |
| `maintenanceCarryTicks` | メンテナンスの端数が毎回切り捨てられ、感情の回復が実際より遅くなる |
| Observer の `combatSequence` | 戦闘 ID が `#c0` から振り直され、別セッションの戦闘が同じ ID を持つ |
| 内部状態・選好・エピソード記憶・空間記憶 | 履歴そのものが失われる |
| Observer の `visitedBiomes` / `lastPetTick` / `lastSleepEventTick` / `separationReported` | 訪問済みバイオームが再び「新規」になり、撫でのクールダウンが無効化され、同じ出来事が重複発火する |

Observer の一時状態（直前 HP、敵の在否、進行中の戦闘）は**あえて保存しません**。
再起動直後に「HP が減った」「敵が現れた」と誤検出するほうが有害だからです。
未解決の戦闘エピソードは保存されないため、再起動を跨いだ戦闘は成功として数えません（過小計上を選ぶ）。

### 再現性テスト

| テスト | 内容 |
|---|---|
| Test A | 同一シード・同一イベント列 ⇒ 同一行動列・同一内部状態 |
| Test B | 途中でセーブ・復元して続けた行動列が、切らずに続けた場合と完全一致すること |
| Test C | 個体シードが違えば分岐しうる一方、同じ履歴なら関係的状態は一致すること |

**ロード直後に余分な決定をしない**ことも重要です。以前は「直近の決定記録が手元にあるか」で
即時決定を判断していましたが、この記録は保存されない一時オブジェクトなので、
復元した個体は必ず 1 回余分に決定していました。余分な決定は `decisionIndex` を 1 つ進め、
以降の乱数列と行動列を継続実行とずらします。現在は
`DecisionTiming`（Minecraft 非依存）が「行動が決まっているか」「緊急か」「経過 tick」だけで判断します。

Test B では `decisionIndex` をあえて落とした復元も行い、**行動列が実際に変わること**を確認しています
（保存が必要な理由をテストとして固定するため）。旧形式のセーブ（`currentBehavior` 欠損）や
未知の行動 ID を読んでも例外を投げないことも検証しています。実験データを読めなくして失うほうが重大です。

---

## 7. 実験条件 Adaptive / Static

| 条件 | 内部状態の持続更新 | エピソード記憶 | 学習済み選好 | スコア式 |
|---|---|---|---|---|
| `static` | ✗ | ✗ | ✗ | Base + Context + TemporaryState |
| `state_only` | ✓ | ✗ | ✗ | Base + Context + State |
| `adaptive` | ✓ | ✓ | ✓ | Base + Context + State + Memory + LearnedPreference |

**重要：STATIC をバニラのオオカミにしてはいけません。** STATIC も同じ 8 行動・同じ BehaviorScorer・
同じ Softmax を使います。そうしないと「行動レパートリの豊かさ」と「経験への適応」を分離できません。
`state_only` は ablation 条件で、「単純な内部数値」が効いたのか「エピソード記憶」が効いたのかを切り分けます。

---

## 8. 研究データスキーマ

### `wolves.json`

```json
{
  "schemaVersion": 1,
  "parameterSetVersion": 2,
  "exportedAtEpochMillis": 1700000000000,
  "exportedAtIso": "2023-11-14T22:13:20Z",
  "worldTime": 24000,
  "experimentSeed": 20260919,
  "wolfCount": 1,
  "wolves": [
    {
      "wolfUuid": "…",
      "ownerUuid": "…",
      "experimentalCondition": "adaptive",
      "randomSeed": 4235793434429632622,
      "decisionIndex": 0,
      "createdAtEpochMillis": 1700000000000,
      "lastUpdatedEpochMillis": 1700000000000,
      "lastUpdatedWorldTime": 24000,
      "state": { "trust": 0.73, "attachment": 0.5, "fear": 0.3, "stress": 0.2,
                 "curiosity": 0.5, "autonomy": 0.5, "energy": 1.0 },
      "learnedPreferences": { "follow_close": 0.0, "…": 0.0 }
    }
  ]
}
```

### `events.jsonl`（1 行 1 決定 / JSONL）

```json
{"recordType":"decision","schemaVersion":2,"epochMillis":…,"worldTime":…,
 "wolfUuid":"…","subjectId":"subject-001","experimentalCondition":"adaptive",
 "experimentSeed":20260919,"randomSeed":…,"decisionIndex":0,
 "dimension":"minecraft:overworld","biome":"minecraft:plains","x":12.5,"y":70.0,"z":-3.25,
 "selectedBehavior":"follow_normal","selectedProbability":0.31,"randomValue":0.42,"temperature":0.6,
 "state":{"trust":0.73,"…":0.0},
 "relevantMemoryIds":["mem-a","mem-b"],
 "behaviorScores":[
   {"behavior":"follow_close","base":0.0,"state":-0.52,"context":0.125,"memory":0.0,
    "preference":0.0,"persistence":0.0,"penalty":0.0,"finalScore":-0.395,
    "probability":0.024,"selected":false}
 ]}
```

**選ばれた行動だけを保存してはいけません。** 全候補のスコア内訳・確率・乱数値を残すことで、
「なぜその行動をしたのか」を論文中で完全に説明・再現できます。

### 個人情報の扱い

プレイヤー名は保存しません。飼い主は匿名化済みの研究用 ID（`subjectId`）としてのみ記録されます。

**実験者が入力するラベル（`sessionLabel`・割付 ID）はログにそのまま残ります。**

> DO NOT use participant names, email addresses, student IDs, UUIDs, or other identifying information.
> Use anonymous labels such as p001-s01.

ラベルと割付 ID には英数字・`_`・`-`（割付 ID は `.` も）だけが使えます。`@`・空白・UUID の形
（ハイフンあり・なしの 32 桁の 16 進）はコマンドが拒否します。学籍番号や氏名のローマ字は形だけでは見分けられないので、
参加者との対応表は**ログの外**（紙や鍵付きのファイル）で管理してください。

### `events.jsonl`（1 行 1 出来事）

```json
{"recordType":"event","schemaVersion":2,"epochMillis":…,"worldTime":12345,
 "wolfUuid":"…","subjectId":"subject-abc123def456","experimentalCondition":"adaptive",
 "eventType":"player_protected_wolf","dimension":"minecraft:overworld","biome":"minecraft:taiga",
 "region":"minecraft:overworld:0,2,-1","x":8.5,"y":63.0,"z":-12.25,
 "actorId":"subject-abc123def456","wolfHealthRatio":0.4,"playerHealthRatio":0.9,
 "valence":0.9,"arousal":0.7,"salience":0.9,"createdMemoryId":"…#m17",
 "metadata":{"combatId":"…#c3","combatTrigger":"wolf_damaged",
             "hostileId":"…","hostileType":"minecraft:zombie"},
 "stateBefore":{"trust":0.520,"attachment":0.550,"fear":0.620,"…":0.0},
 "stateAfter":{"trust":0.600,"attachment":0.630,"fear":0.550,"…":0.0},
 "stateDelta":{"trust":0.080,"attachment":0.080,"fear":-0.070,"…":0.0},
 "preferenceBefore":{"follow_close":0.120,"…":0.0},
 "preferenceAfter":{"follow_close":0.150,"…":0.0},
 "preferenceDelta":{"follow_close":0.030,"…":0.0}}
```

`schemaVersion` は 3 です（パラメータセットのバージョンとは独立に上がります）。

**適用の前後を両方残しているのが要点です。** 適用後の値だけでは、その出来事がどれだけ効いたかを
測れません。trust が 0.60 になったと分かっても、0.52 から上がったのか 0.59 から上がったのかで
意味がまるで違い、飽和しかけた個体では後者ばかりになります。`stateDelta` / `preferenceDelta` は
引き算で復元できる冗長な情報ですが、記録漏れや二重適用をログ内で検算できます。

`createdMemoryId` で `memories.jsonl` の該当行へ、`combatId` で同じ戦闘から出た他のイベントへ
それぞれ辿れます。

`metadata` は戦闘由来の出来事にだけ付きます。`combatId` で 1 回の戦闘をまとめられるので、
**1 戦闘から 2 件以上発火していないか**を解析側で検証できます（`events_by_wolf.csv` で確認可能）。

### `damage.jsonl`（1 行 1 被弾）

```json
{"recordType":"damage","schemaVersion":3,"damageDefinition":"EFFECTIVE_HEALTH_LOSS",
 "worldTime":12345,"wolfUuid":"…","subjectId":"subject-abc123def456",
 "experimentalCondition":"adaptive",
 "targetType":"wolf","attackerType":"hostile","attackerId":"a1b2c3d4",
 "directSourceType":"minecraft:arrow","causingSourceType":"minecraft:skeleton",
 "actualDamage":3.0,"healthLoss":3.0,"absorptionLoss":0.0,
 "healthBefore":20.0,"healthAfter":17.0,"absorptionBefore":0.0,"absorptionAfter":0.0,
 "fatal":false,"combatId":"…#c2"}
```

**これは学習用のログではありません。** 研究上の出来事（`wolf_damaged` など）は決定ごとに
最大 1 件へ集約されますが、こちらは物理的な被弾 1 発につき 1 行を残します。集約の前に
何が起きていたかを後から検証するためだけのもので、内部状態も選好もここからは動きません。

* `targetType` — `wolf` / `owner` / `hostile`
* `attackerType` — `hostile` / `owner` / `wolf` / `environment` / `other`
* `directSourceType` は直接ぶつかったもの（矢）、`causingSourceType` は原因（射手）
* `damageDefinition` を毎行に書くのは、解析時に `actualDamage` の意味が
  分からなくなるのを防ぐためです
* `fatal` が真なら、この一撃で対象が死亡しています

### `memories.jsonl`（1 行 1 記憶）

```json
{"recordType":"memory","wolfUuid":"…","memoryId":"…#m17","worldTime":12345,
 "eventType":"near_death","region":"minecraft:overworld:3,1,-2","valence":-1.0,
 "arousal":0.9,"salience":1.0,"…":null}
```

`memoryId` は `decisions.jsonl` の `relevantMemoryIds` と対応します。
これを突き合わせると、**どの記憶がどの決定に効いたか**を完全に復元できます。

### Python / pandas からの読み込み

```python
import json, pandas as pd

wolves = pd.json_normalize(json.load(open("wolves.json"))["wolves"])

def read_jsonl(path):
    return [json.loads(line) for line in open(path, encoding="utf-8")]

rows = read_jsonl("decisions.jsonl")
decisions = pd.json_normalize(rows)
scores = pd.json_normalize(rows, "behaviorScores",
                           ["wolfUuid", "decisionIndex", "worldTime", "experimentalCondition"])

events = pd.json_normalize(read_jsonl("events.jsonl"))
memories = pd.json_normalize(read_jsonl("memories.jsonl"))

# どの記憶がどの決定に効いたかを復元する
links = decisions.explode("relevantMemoryIds").merge(
    memories, left_on="relevantMemoryIds", right_on="memoryId", suffixes=("", "_mem"))
```

---

## 8.5 実験前シミュレーション

ユーザ実験を始める**前に必ず実行する**こと。同一の初期状態から出発した仮想個体を 2 群に分け、
異なるイベント履歴を与えてから同一のテスト文脈に置き、行動分布が本当に分離するかを確認する。
ここで分離しないなら、Minecraft 上でユーザ実験をしても個体差は知覚されない。

```bash
./gradlew simulate
./gradlew simulate -PsimArgs="--wolves=50 --events=120 --decisions=200 --temperature=0.6 --seed=20260919"
```

測定は**閉ループ**で行う。飼い主との距離は選ばれた行動の結果として変化し（接近すれば近づき、
探索すれば離れる）、脅威・夜・地下の発生スケジュールは全個体で完全に同一である。
したがって観測された差は育成履歴のみに由来する。

### 現在のパラメータでの結果（wolves=25, events=120, τ=0.6）

| condition | FOLLOW_CLOSE | EXPLORE | APPROACH | 平均 owner distance | within | between |
|---|---:|---:|---:|---:|---:|---:|
| adaptive / 一緒に育成 | 0.158 | 0.041 | 0.682 | 1.4 blocks | 0.091 | 0.846 |
| adaptive / 単独探索 | 0.001 | 0.831 | 0.020 | 31.2 blocks | | |
| state_only / 一緒に育成 | 0.070 | 0.159 | 0.344 | 4.0 blocks | 0.134 | 0.669 |
| state_only / 単独探索 | 0.003 | 0.818 | 0.027 | 31.1 blocks | | |
| static / 一緒に育成 | 0.047 | 0.364 | 0.160 | 8.8 blocks | 0.142 | 0.075 |
| static / 単独探索 | 0.042 | 0.359 | 0.156 | 8.6 blocks | | |

読み取れること：

* **ADAPTIVE では `within < between`**（0.09 < 0.85）。同一個体の行動指紋は安定しており、
  育成履歴の異なる個体とは大きく離れる。H1・H2 がシミュレーション上で支持されている。
* **STATIC では `within > between`**（0.14 > 0.08）。対照条件として正しく機能しており、
  かつバニラ化していない（8 行動すべてが一定割合で選ばれている）。
* **STATE_ONLY は中間**。エピソード記憶と選好学習の寄与分を ablation で切り分けられる。

### 既知のチューニング課題

現在の定数では、120 イベント程度の育成で `attachment` が上限 1.0 まで飽和し、
一緒に育成した個体の `APPROACH_PLAYER` が 0.68 と支配的になる。個体差は明確に出るが、
分布としてはやや極端で「一つの行動しかしない」ように見えるおそれがある。
実験前 pilot では次のいずれかで調整するとよい（いずれも `AiWolfParameters` の 1 行）。

| 調整 | 効果 |
|---|---|
| `SOFTMAX_TEMPERATURE` を 0.6 → 1.0〜1.2 | 分布がなだらかになる（分離度は 0.85 → 0.62 程度へ低下） |
| `StateImpactMatrix` の関係性状態の増分を半分にする | 飽和までの育成時間が延びる |
| `PREFERENCE_LEARNING_RATE` を 0.03 → 0.015 | 選好の成長が緩やかになる |

なお `APPROACH_PLAYER` の過剰選択は、実機では「接近すると距離が縮まり、
`NEAR_OWNER` が効いて選ばれにくくなる」という負のフィードバックで緩和される。
最終的な定数決定は `ContextBuilder` / `BehaviorExecutor` の実装後に行うこと。

---

## 8.5.1 シミュレーションとゲーム内の対応

事前シミュレーションで得た見立ては、**そこで使っている出来事がゲーム内でも起きて初めて**実機に当てはまります。
Phase 13 の調査時点では `player_protected_wolf` がシミュレーションの中心的な出来事でありながら
ゲーム内に発火経路がなく、2 つは別の世界をモデルしていました。現在はすべて対応しています。

| 出来事 | シミュレーション | ゲーム内の検出元 |
|---|---|---|
| `pet` | ✓ | `WolfInteractionHandler`（スニーク＋素手＋右クリック、クールダウン 200 tick） |
| `feed` | ✓ | `WolfInteractionHandler`（肉類を与える） |
| `player_protected_wolf` | ✓ | `CombatEpisode`（被弾 → 飼い主の攻撃 → 撃破） |
| `defend_success` | ✓ | `CombatEpisode`（飼い主の被弾 → オオカミの介入 → 撃破） |
| `player_and_wolf_combat` | — | `CombatEpisode`（双方関与だが上記に当てはまらない） |
| `monster_encounter` | ✓ | `WolfEventObserver`（敵性 MOB が**現れた瞬間**のみ） |
| `safe_solo_exploration` | ✓ | `WolfEventObserver`（探索継続・無被弾） |
| `new_biome` | ✓ | `WolfEventObserver`（未訪問バイオーム。訪問済み集合は永続化） |
| `sleep_near_player` | ✓ | `WolfEventObserver`（飼い主の就寝、1 夜 1 回） |
| `long_separation` | — | `WolfEventObserver`（飼い主不在の継続） |
| `reunion` | — | `WolfEventObserver`（分離後の再会） |
| `player_damaged_near_wolf` | — | `WolfEventObserver`（飼い主の HP 減少） |
| `wolf_damaged` | — | `WolfEventObserver`（HP 減少） |
| `wolf_damaged_during_exploration` | — | `WolfEventObserver`（探索中の HP 減少） |
| `wolf_low_health` | — | `WolfEventObserver`（HP 比率 < 低 HP 閾値） |
| `near_death` | — | `WolfEventObserver`（HP 比率 < 緊急閾値） |

「シミュレーション —」の行は、シミュレーションで使っていないだけでゲーム内では発生します。
逆向きの欠け（シミュレーションだけで起きる出来事）は `EventParityTest` が回帰テストとして禁止しています。
1 回の被弾からは HP 比率に応じて 4 種のうち **1 件だけ**発火します（`if / else if` 連鎖）。

---

## 8.6 行動指紋の測定

個体の行動傾向は `/wolfai signature` で測ります。**自由行動のログから個体間で
行動分布を比べてはいけません。** 個体ごとに置かれていた状況が違うため、
「愛着が育ったから飼い主の近くにいた」のか「単に飼い主がそこにいた」のかを区別できません。

`/wolfai signature` は全個体を**まったく同一の標準文脈系列**に置いて測るので、
差を履歴に帰属できます。シミュレーションと literally 同じコード
（`StandardTestBattery`）を使うため、in-silico の結果と実測を直接比較できます。

同じセッション番号で測れば結果は完全に一致し（再現性）、別セッションなら
サンプリング誤差の分だけ揺れます（H2 の測定）。`/wolfai signature` は
ワールド時刻をセッション番号にするので、**時間をおいて 2 回以上測ってください**。

手順の全体は `docs/pilot-protocol.md` にあります。

---

## 9. Build 方法

```bash
./gradlew build          # jar を build/libs/ に生成
./gradlew test           # 単体テスト（Minecraft 非依存、400 件以上）
./gradlew simulate       # 実験前シミュレーション（Minecraft 非依存）
./gradlew runClient      # 開発用クライアント
./gradlew runServer      # 開発用サーバ
```

### 開発環境の注意：プレイヤー ID の固定

Minecraft のクライアントは `--username` の既定値を `Player` + 起動時刻の下 3 桁 で生成するため、
`runClient` のたびにユーザ名が変わり、オフライン UUID も変わります。すると前回テイムした
オオカミの飼い主が「別人」になり、`/wolfai status` が
「あなたが飼い主の個体はいません」と返します。

`build.gradle` の `loom.runs.client` で `--username` を固定してあるので、
この問題は起きません。値を変えるとそれ以前にテイムした個体との紐付けが切れるので、
実験用ワールドを使い回す場合は変更しないでください。

```groovy
loom {
    runs {
        client {
            programArgs '--username', 'Researcher'
        }
    }
}
```

---

### バージョンと選定理由

| 項目 | 値 |
|---|---|
| Minecraft | 1.21.1 |
| Yarn mappings | 1.21.1+build.3 |
| Fabric Loader | 0.16.5 |
| Fabric API | 0.102.0+1.21.1 |
| Fabric Loom | 1.7-SNAPSHOT |
| Java | 21 |

新規プロジェクトのため、以下の理由で 1.21.1 を選定しました。

* Fabric API・Yarn mappings・主要ライブラリが揃っており、MOD エコシステムが安定している
* Java 21（LTS）を要求するバージョンで、長期の研究運用に向く
* 実験参加者が用意しやすい広く使われているバージョンである

**このバージョンは実験条件の一部です。再検証なしに変更しないでください。**

---

## 10. Commands

すべて **operator 権限（レベル 2）** を要求します。

```
/wolfai status                             近くの自分のオオカミの状態を表示
/wolfai list                               このワールドで追跡中の全個体を一覧
/wolfai debug on|off                       デバッグ表示（アクションバー）の切り替え
/wolfai reset                              近くのオオカミを初期状態へ戻す
/wolfai mode adaptive|state_only|static    これ以降に作られる個体の実験条件を切り替える
/wolfai export                             現時点の研究データを書き出す
/wolfai signature [ラベル]                 標準テストバッテリで行動指紋を測る
```

Human Study のセッション（Phase 20〜25）：

```
/wolfai session start <label>              セッション開始（例：p001-s01）。版の情報を sessions.jsonl に記録
/wolfai session stop | status | help       終了・状態・使い方と個人情報の注意
/wolfai phase set BASELINE|TRAINING|MEMORY_TEST|EVALUATION|FREE_PLAY
/wolfai phase clear
/wolfai assignment set <id> | order <条件>条件> | table | status | clear
/wolfai memory condition on|off            Study 2：記憶だけを ON / OFF（セッション中のみ）
```

**ラベル・割付 ID に参加者を特定できる情報を入れないでください**（DO NOT use participant names, email addresses,
student IDs, UUIDs, or other identifying information. Use anonymous labels such as p001-s01.）。
通しの手順は [`docs/HUMAN_STUDY_DRY_RUN.md`](docs/HUMAN_STUDY_DRY_RUN.md) にあります。

Snapshot（`/wolfai snapshot save|restore|list`）と評価モード（`/wolfai evaluation start|stop`）は、
Demo Mode 中に加えて**実験セッション中にも**使えます。

**Evaluation freeze currently applies to all registered research wolves.** 評価モードの学習の凍結は、
選んだ個体だけでなく登録されている全研究オオカミにかかります。参加者 1 人・研究オオカミ 1 匹のパイロットでは問題になりません。

**`/wolfai mode` は既存個体の条件を変えません。** 変わるのは、これ以降に新しく研究 AI の
管理下へ入る個体の既定条件だけです。すでに育っている個体の条件を途中で変えることはできず、
またすべきでもありません（条件を跨いだ個体のデータは解析から除外する必要があります）。
実験開始前に `config/aiwolf.properties` の `defaultCondition` で決めておいてください。

`signature` は**個体の状態を一切変更しません**（読むだけ、乱数も測定専用ストリーム）。
何度測ってもその後の振る舞いに影響しないので、安心して繰り返せます。

**通常プレイ時には内部数値を表示しません。** デバッグ表示は既定で無効で、
operator が明示的に有効化したプレイヤーにしか出ません。参加者が
「性格値を見たから個性を判断した」という交絡を防ぐためで、研究の妥当性に直結します。

---

## 11. 研究ログ保存場所

ワールド内の `aiwolf-research/` に出力されます。ワールドと一緒に残るので、
セーブデータを渡せば研究データも付いてきます。

```
<world>/aiwolf-research/
├── decisions.jsonl              行動決定のたびに 1 行（継続記録）
├── events.jsonl                 出来事のたびに 1 行（継続記録）
├── signatures.jsonl             /wolfai signature のたびに 1 行
├── damage.jsonl                 被弾 1 発ごとに 1 行（学習には使わない検証用）
├── sessions.jsonl               Human Study のセッション開始・フェーズ・終了・実験者の操作
├── player-events.jsonl          プレイヤーの行動（撫で・餌・攻撃など）
├── player-samples.jsonl         セッション中、1 秒ごとの飼い主とオオカミの関係
├── ai-availability.jsonl        研究 AI が止まった・再開した時刻
├── demo/                        Demo Mode 中のログ（本実験の解析に混ぜない）
└── export-20260921-153000/      /wolfai export のたびに作られる
    ├── wolves.json              その時点の全個体のスナップショット
    └── memories.jsonl           その時点の全エピソード記憶
```

すべての行の先頭には `logEnvelopeVersion`・`runId`・`sessionId`・`phaseId`・`logSequence` が付きます。
`logSequence` は 1 回のサーバ起動の中でファイルをまたいで通しの番号なので、`runId` → `logSequence` の順に並べれば
記録した順序を復元できます（項目の一覧は [`docs/research-log-schema.md`](docs/research-log-schema.md)）。

**サーバスレッドをブロックしません。** 行はメモリ上のバッファへ積み、30 秒ごと、
またはバッファが 2000 行を超えたときだけ、IO ワーカスレッドでまとめて追記します。

### 実験設定ファイル

`config/aiwolf.properties` に実験メタデータを置きます。初回起動時に自動生成されます。

```properties
experimentSalt=-3921847362819374621
experimentSeed=20260919
defaultCondition=adaptive
humanStudyProtocolVersion=1
```

* `experimentSalt` — 匿名 ID は `SHA-256(salt + playerUUID)` から作ります。
  **実験開始時に一度決めて以後変更しないでください。** 変えると同じ参加者が別 ID になり、
  セッションを跨いだ履歴の追跡が切れます。秘密である必要はありませんが、
  固定値を Git へ置かないため初回起動時にランダム生成してローカルへ書き出します。
* `experimentSeed` — 行動選択の再現に使います。個体シードはここと UUID から導出されます。
* `defaultCondition` — 新規個体へ割り当てる条件（`adaptive` / `state_only` / `static`）。
* `humanStudyProtocolVersion` — Human Study の**実験手順**の版（フェーズの順序・長さ・教示など）。
  AI のパラメータの版（`parameterSetVersion`）とは別物です。手順を変えたら上げてください。
  セッション開始の行に、ビルド時に埋め込まれた `modVersion` / `buildRevision`（Git の commit）/ `buildDirty` と一緒に記録されます。
  この行がない古い設定ファイルでは 1 になります。

壊れた値や欠損した値は既定値へフォールバックします（設定ファイルの破損で
実験セッションを丸ごと失わないため）。設定ファイルの値はワールドに保存された値より優先されます。

### 匿名化

プレイヤー名も UUID も研究データには残りません。ワールドごとにランダムな salt を持ち、
`SHA-256(salt + uuid)` の先頭 12 桁を `subject-xxxxxxxxxxxx` として使います。
同じワールド内では同じ参加者が常に同じ ID になるため「同一参加者との共有経験」は追跡でき、
salt を破棄すれば以後 ID から個人を辿ることはできません。
salt はワールドのセーブデータに保存されるので、**変更すると履歴の連続性が切れます**。

これは研究ログのすべての系統に適用されます。とくに `damage.jsonl` の `attackerId` は、
加害者がプレイヤーなら匿名 ID、Mob なら短縮 UUID です。Mob の UUID はそのワールド限りの
内部識別子で、戦闘エピソードと突き合わせるために必要ですが、プレイヤーの UUID は
アカウントに紐づく個人情報なので、**短縮した部分文字列であっても残しません**。
弓で撃った場合も、矢ではなく匿名化された射手へ帰属します。

`DamageLogAnonymityTest` が、既知の UUID を入力したときに生成される JSON へ
生 UUID もその先頭 8 文字も現れないことを回帰テストとして固定しています。

## 12. 実装状況

| ステップ | 内容 | 状態 |
|---|---|---|
| 1 | WolfInternalState（状態モデル・clamp・分類・EMA） | ✅ 完了 |
| 2 | WolfContextSnapshot（文脈の正規化・脅威スコア） | ✅ 完了 |
| 3 | BehaviorScorer（内訳つきスコア・条件別の成分切替） | ✅ 完了 |
| 4 | PolicySelector（Softmax・決定論的乱数・再現性） | ✅ 完了 |
| 5 | ResearchLogger（バッファ付き JSONL 出力） | ✅ 完了 |
| 6 | Event Observer（PET / FEED / ダメージ / 分離・再会 / 新バイオーム等の検出） | ✅ 完了 |
| 7 | Internal State Update（StateUpdater・StateImpactMatrix） | ✅ 完了 |
| 8 | Episodic Memory（WolfMemory・MemoryStore・剪定） | ✅ 完了 |
| 9 | Memory Retrieval（Top-K・relevance・影響計算） | ✅ 完了 |
| 10 | Preference Learning（PreferenceVector・EventImpactMatrix） | ✅ 完了 |
| — | 空間記憶（region 量子化・fear / positive / visitCount） | ✅ 完了 |
| — | WolfAiAgent（AI コアのランタイム） | ✅ 完了 |
| — | 実験前シミュレーション | ✅ 完了 |
| — | ContextBuilder / BehaviorExecutor / AiWolfGoal（Minecraft 接続） | ✅ 完了 |
| — | `/wolfai` コマンドとデバッグ表示 | ✅ 完了 |
| — | PET インタラクション（スニーク＋素手＋右クリック） | ✅ 完了 |
| — | 永続化（PersistentState・NBT 変換） | ✅ 完了 |
| — | 研究ログのファイル出力と `/wolfai export` | ✅ 完了 |
| — | 匿名 ID への変換（salt 付きハッシュ） | ✅ 完了 |
| — | 戦闘エピソード追跡（`CombatEpisode` / `CombatTracker` / `CombatEvents`） | ✅ 完了 |
| — | `PLAYER_PROTECTED_WOLF` のゲーム内発火 | ✅ 完了 |
| — | region 中心基準の `AVOID_LOCATION` | ✅ 完了 |
| — | 実行中行動・決定周期・Observer 状態の永続化（NBT format 3 で拡張） | ✅ 完了 |
| — | 実験設定ファイル（`aiwolf.properties`：salt / seed / 既定条件） | ✅ 完了 |
| — | 再現性テスト A / B / C とイベント対応の回帰テスト | ✅ 完了 |
| — | 実機での定期メンテナンス（経過 tick 基準・シミュレーションと整合） | ✅ 完了 |
| — | 設定した既定条件のレジストリへの反映 | ✅ 完了 |
| — | 距離を含む飼い主在否の単一定義（`OwnerPresence`） | ✅ 完了 |
| — | 飼い主被弾を起点とする戦闘エピソード（`defend_success` の実機成立） | ✅ 完了 |
| — | ロード直後の余分な決定の除去（`DecisionTiming`） | ✅ 完了 |
| — | セッションを跨いで一意な戦闘 ID（`CombatIdSequence`） | ✅ 完了 |
| — | 出来事ログの stateBefore / stateDelta / preferenceDelta（schema v3） | ✅ 完了 |
| — | 戦闘の結末を敵 UUID で通知（退避中の個体にも届く） | ✅ 完了 |
| — | 飼い主の遠距離攻撃（弓・クロスボウ）による介入の記録 | ✅ 完了 |
| — | 実 HP 損失によるダメージ定義と致死攻撃の帰属（観測用 Mixin） | ✅ 完了 |
| — | 救援後に再被弾しても救出が成立する時系列判定 | ✅ 完了 |
| — | 研究ログ内のプレイヤー識別子の匿名化（damage.jsonl） | ✅ 完了 |
| — | ダメージを実イベントで捕捉（矢は射手へ帰属・相手ごとに分離） | ✅ 完了 |

> 保存形式は **NBT format version 3**（`WolfAiNbt.FORMAT_VERSION`）です。
> 古い形式のセーブも読めます（追加キーはすべて任意扱い）。
>
> 個体の状態はワールドのセーブデータ（`data/aiwolf_research.dat`）に保存されます。
> チャンクのアンロード、ディメンション移動、サーバ再起動を跨いでも失われません。
> 決定インデックスと記憶 ID の連番も保存するため、再起動後も乱数列が続き再現性が保たれます。

---

## 13. テスト

```bash
./gradlew test
```

Minecraft 本体に依存しない単体テストが 400 件以上あります（件数は増え続けるので固定の数は書きません。`./gradlew test` の出力で確認してください）。
解析スクリプトのテストは `python -m unittest discover -s analysis -p "test_*.py"` で実行します。

* `MathSupportTest` — clamp / z 変換 / 距離正規化 / softmax の数値安定性
* `WolfInternalStateTest` — clamp、初期値の全個体同一性、感情状態のベースライン回帰、互換性
* `WolfEventTypeTest` — salience の計算式と値域
* `StateUpdaterTest` — salience による重み付け、条件別の更新範囲、energy 動態、飽和時の clamp
* `WolfRandomProviderTest` — 決定論性、復元後の再現、一様性
* `PreferenceVectorTest` — 初期値 0、1 イベントでの微小変化、忘却、クリップ
* `MemoryStoreTest` — 容量上限、重要な記憶の生存、保持スコア
* `MemoryRetrieverTest` — Top-K の順序、relevance の内訳、決定論性、記憶由来の寄与
* `SpatialMemoryTest` — region 量子化（負座標含む）、恐怖の蓄積と相殺、剪定
* `BehaviorEligibilityTest` — 実行可能行動集合の判定
* `BehaviorScorerTest` — 仕様書の計算例（StateEffect = 1.10）、条件別の成分切替、緊急退避
* `PolicySelectorTest` — 確率の正規化、経験分布と理論分布の一致、シード再現性
* `WolfAiAgentTest` — 条件ごとの蓄積範囲、育成による分布の分岐、行動列の完全再現
* `EmergentIndividualityTest` — **H1 の検証**：同一初期値でも履歴が異なれば行動分布が分かれること、
  および同一履歴なら UUID が違っても分布が一致すること（個体差が乱数由来でないこと）
* `PreExperimentSimulationTest` — 100 匹規模のシミュレーションで ADAPTIVE が分離し
  STATIC が分離しないことの回帰テスト
* `DecisionLogJsonTest` — 研究ログに全成分が含まれること、個人情報が入らないこと
* `WolfRecordJsonTest` — `wolves.json` のスキーマ、育成結果の反映、個人情報の不在
* `AnonymousIdMapperTest` — 匿名 ID の安定性・非可逆性・衝突耐性
* `EventLogJsonTest` — 出来事ログと記憶ログの形式、記憶 ID の突き合わせ可能性
* `StandardTestBatteryTest` — 測定手続きの再現性、個体を変更しないこと、履歴による分離
* `CombatEpisodeTest` — 救出・防衛成功の成立条件、飼い主が攻撃していない場合の不成立、
  別 MOB の撃破・オオカミ死亡・敵の逃走での不成立、同一戦闘からの重複発火の禁止
* `ReproducibilityTest` — **Test A / B / C**。セーブ・復元後も行動列が一致すること、
  `decisionIndex` を落とすと実際に壊れること、旧形式セーブと未知の行動 ID の安全な読み込み
* `CausalChainConsistencyTest` — 全出来事が state・記憶・選好の 3 経路へ入ること、
  符号が経路間で矛盾しないこと、1 出来事が各経路へちょうど 1 回だけ入ること
* `EventParityTest` — 定義済みの出来事すべてにゲーム内の発火経路があること、
  シミュレーション専用の出来事が存在しないこと、製品コードに性格ラベルが現れないこと
* `AiWolfConfigTest` — 設定の往復、壊れた値・欠損値のフォールバック、salt と匿名 ID の対応
* `Vector3Test` / `AvoidanceGeometryTest` — 逃走方向の計算、ゼロ長正規化で NaN を出さないこと
* `DamageAccumulatorTest` — 相手ごとの分離（合算しないこと）、代表の選択規則 3 段、
  重さの判定に区間最低 HP を使うこと、決定ごと 1 件の発火、閾値の扱い
* `DamageAttributionTest` — 矢が射手へ帰属すること、環境ダメージと自傷を戦闘にしないこと
* `DamageSeverityTest` — 1 回の被弾から 1 種別だけを返すこと、閾値の境界
* `EnvironmentalDamageTest` — 加害者 {@code null} での例外非発生、完全ブロックを学習しないこと
* `CombatNotificationTest` — 退避した個体にも結末が届くこと、通知が距離で絞られていないこと、
  HP 差分ポーリングが残っていないこと、経路の役割分離、Mixin が観測だけであること
* `OwnerProjectileInterventionTest` — 弓での救援が成立すること（Test A）、
  無関係な敵を撃っても成立しないこと（Test B）、複数回撃っても発火は 1 件（Test C）、
  近接経路が壊れていないこと（Test D）
* `DamageRecordTest` — `EFFECTIVE_HEALTH_LOSS` の定義、防具軽減、Absorption、
  致死攻撃、被害 0、環境ダメージ
* `DamageLogJsonTest` — 被弾ログの全項目、矢と射手の両方の保持、個人情報の不在
* `DamageLogAnonymityTest` — **匿名化の回帰テスト**。既知の UUID から生成したログに
  生 UUID も先頭 8 文字も現れないこと、矢がプレイヤーの匿名 ID へ帰属すること、
  Mob の識別子は維持されること
* `MaintenanceParityTest` — 経過 tick が同じなら決定間隔が違っても内部状態が一致すること、
  端数の持ち越しと永続化、時刻が大きく飛んだときの打ち切り
* `DecisionTimingTest` — 復元した個体がロード直後に余分な決定をしないこと、緊急時の即時再評価
* `SaveLoadContinuityTest` — **Phase 13.1 の最重要テスト**。保存・復元を挟んでも
  決定時刻・decisionIndex・行動列・確率分布・乱数値がすべて一致すること
* `SimulationRuntimeParityTest` — シミュレーション経路と実機経路が同じ入力から
  同じ内部状態・選好・記憶・行動列へ到達すること（3 条件すべてで）
* `OwnerPresenceTest` — 距離を含む在否判定、境界、別ディメンション、
  遠距離での分離成立・接近での再会成立
* `NaturalCombatFlowTest` — 飼い主被弾を起点とする防衛成功（Case A–D）
* `CombatIdSequenceTest` — 戦闘 ID が再起動を跨いでも衝突しないこと
* `EventImpactMagnitudeTest` — 1 回の出来事で行動確率が極端に変わらないこと、
  飽和した個体では同じ出来事でも変化が小さくなること
* `ResearchConditionRegressionTest` — 3 条件の蓄積範囲の違いと、
  行動集合・スコア式・実行系が条件によらず共通であること
* `DefaultConditionTest` — 設定した既定条件が新規個体へ実際に適用されること

---

## 14. 手動確認シナリオ

`./gradlew runClient` で起動し、まず `/wolfai debug on` を実行する。
各シナリオは**そのイベントが実際に発火するか**を確認するためのもので、
自動テストでは代替できない（Minecraft 側の検出経路を通るため）。

ログの場所：ワールドのセーブフォルダ内 `aiwolf-research/events.jsonl`（1 行 1 出来事）。
テキストエディタで開けます。以降「イベントログ」と書いたらこのファイルのことです。

### シナリオ 1：時間による内部状態の回復

1. オオカミをテイムし、`/wolfai status` で `fear` の値を控える。
2. `/summon zombie ~ ~ ~3` でゾンビを出し、オオカミを何度か攻撃させる。
   デバッグ表示の `fear` が上がることを確認する。
3. ゾンビを倒し、安全な場所で 5 分ほど放置する（`/time set day` で明るくすると楽）。
4. `/wolfai status` を数分おきに実行する。
   **成功時**：`fear` が少しずつ 0.30（初期値）へ近づいていく。
5. `energy` が行動に応じて変わることも確認する。
   探索中は下がり、`rest_near_player` の間は戻る。

> 何分経っても `fear` が動かない場合、メンテナンスが呼ばれていない。
> Phase 13.1 以前の状態に戻っている。

### シナリオ 2：長期分離と再会

1. オオカミをその場に残し、**50 block 以上**離れる（`/tp` でもよい）。
2. そのまま **2 分以上**待つ（`LONG_SEPARATION_TICKS` = 2400 ticks）。
3. イベントログに `"eventType":"long_separation"` が **1 件だけ**出ることを確認する。
   離れ続けても 2 件目は出ない。
4. オオカミのところへ戻る（48 block 以内）。
5. **成功時**：`"eventType":"reunion"` が 1 件出る。`/wolfai status` で `attachment` が上がる。

> 48 block 以内をうろうろしているだけでは分離は起きない。これは仕様。

### シナリオ 3：飼い主を守る（`defend_success`）

1. `/time set night` か `/summon zombie ~ ~ ~5` で敵を出す。
2. **ゾンビに自分を攻撃させる**（オオカミではなく自分が先に殴られる）。
3. オオカミが戦闘へ介入するのを待つ。
4. ゾンビが倒される（誰が最後の一撃でもよい）。
5. **成功時**：イベントログに `"eventType":"defend_success"` が 1 件。
   `metadata.combatTrigger` が `"owner_damaged"`、`metadata.combatId` が入っている。
6. 同じ行の `stateBefore` と `stateAfter` を見比べ、`trust` が上がっていることを確認する。

> オオカミが一度も攻撃していなければ発火しない。自分ひとりで倒した場合も発火しない。

### シナリオ 4：飼い主がオオカミを守る（`player_protected_wolf`）

1. ゾンビを出し、**オオカミを先に攻撃させる**（自分は手を出さない）。
2. オオカミが被弾したのを確認してから、自分でそのゾンビを倒す。
3. **成功時**：`"eventType":"player_protected_wolf"` が **1 件だけ**。
   `metadata.combatTrigger` は `"wolf_damaged"`。
4. `/wolfai status` で `trust` と `attachment` の上昇、記憶件数の増加を確認する。
5. イベントログの `preferenceDelta` で `follow_close` と `approach_player` が
   正の値になっていることを確認する。

> 逆順（自分が先に殴った敵に後からオオカミが噛まれる）では
> `player_and_wolf_combat` になる。これも仕様。

### シナリオ 5：セーブ・ロードの再現性

1. `/wolfai status` を実行し、**current behavior**、**decisionIndex**、**worldTime** を控える。
2. ワールドを保存して終了する（Esc →「セーブしてタイトルへ戻る」）。
3. Minecraft を再起動し、同じワールドを開く。
4. すぐに `/wolfai status` を実行する。
   **成功時**：current behavior が 1 の値と同じで、decisionIndex も同じ。
5. そのまま 2〜3 秒待って `/wolfai status` をもう一度実行する。
   **成功時**：decisionIndex が 1 ずつ普通に進む（ロードした瞬間に 1 増えていない）。
6. イベントログに、再起動直後の `wolf_damaged` / `monster_encounter` / `new_biome` が
   **出ていない**ことを確認する。

> 手順 4 で decisionIndex が 1 増えていたら、ロード直後の余分な決定が復活している。

### シナリオ 7：逃げたオオカミにも戦闘の結末が届くか

1. オオカミをゾンビに攻撃させ、被弾させる。
2. オオカミが `RETREAT` を選んで逃げるのを待つ（デバッグ表示で現在行動を確認）。
3. **オオカミが 20 block 以上離れた状態で**、自分でそのゾンビを倒す。
4. **成功時**：イベントログに `"eventType":"player_protected_wolf"` が出る。
   `metadata.combatId` が手順 1 の被弾と同じ戦闘を指している。
5. 何も出なければ、通知が距離で絞られている（Phase 13.2 以前の状態）。

> ここが落ちると、恐怖傾向の強い個体ほど守られた経験が記録されません。
> 測ろうとしている個体差そのものに相関した欠測になるので、必ず確認してください。

### シナリオ 8：Skeleton の矢が射手へ帰属するか

1. `/summon skeleton ~ ~ ~10` でスケルトンを出し、オオカミに矢を当てさせる。
2. **成功時**：イベントログの被弾イベントに
   `"hostileType":"minecraft:skeleton"`、`"damageOrigin":"hostile"` が入る。
   `minecraft:arrow` になっていたら帰属が誤っている。
3. ゾンビとスケルトンに**同時に**攻撃させる。
4. **成功時**：`damageSourceCount` が 2 になり、`hostileType` は
   その 40 tick でより多くのダメージを与えたほうになる。
   `damageTotal` と `damageAttributed` の差から、もう一方の被害も追える。

### シナリオ 9：落下ダメージを戦闘にしないか

1. オオカミを高いところから落として被弾させる（敵は周りにいない状態で）。
2. **成功時**：被弾イベントは出るが `"damageOrigin":"environment"` で、
   `combatId` も `hostileId` も付かない。
3. `/wolfai status` で内部状態（fear など）は動いていることを確認する。

> 落下で戦闘記録を作ってしまうと、相手のいない戦闘が開いて結末が永遠に来ず、
> 同時に進行していた本物の戦闘が枠から追い出されることがあります。

### シナリオ 10：弓による救援（Phase 13.3 の本丸）

1. Adaptive のオオカミをテイムし、`/wolfai debug on` にする。
2. `/summon zombie ~ ~ ~5` でゾンビを出し、**ゾンビにオオカミを攻撃させる**。
3. `damage.jsonl` に `"targetType":"wolf"` の行が出て、`combatId` が入ることを確認する。
4. **弓でゾンビを 1 発だけ撃つ**（倒しきらない）。
5. オオカミにゾンビを倒させる。
6. **成功時**：`events.jsonl` に `"eventType":"player_protected_wolf"` が 1 件出る。
7. `damage.jsonl` で、手順 4 の矢が `"targetType":"hostile"`、
   `"attackerType":"owner"` として記録されていることを確認する。

> 最後の一撃を入れたのはオオカミですが、飼い主は明らかに救援しています。
> ここが落ちると、遠距離で援護する遊び方をする参加者ほど救われた経験が記録されません。

### シナリオ 11：Skeleton の矢の帰属

1. `/summon skeleton ~ ~ ~12` でスケルトンを出し、オオカミを射させる。
2. `damage.jsonl` を開く。
3. **成功時**：`"directSourceType":"minecraft:arrow"`、
   `"causingSourceType":"minecraft:skeleton"`、`"attackerType":"hostile"`。
4. `actualDamage` が `healthBefore − healthAfter` と一致することを確認する。
5. 対応する `combatId` の戦闘が、矢ではなくスケルトンを相手にしていることを確認する。

### シナリオ 12：致死攻撃の帰属

1. `/effect give @e[type=wolf,limit=1] minecraft:instant_damage 1 1` などでオオカミを低 HP にする。
2. ゾンビに倒させる。
3. **成功時**：`damage.jsonl` の最後の行が `"fatal":true` で、
   `attackerId` と `combatId` が入っている。
4. その一撃が **1 行だけ**であることを確認する（死亡経路との二重記録がない）。

> 「オオカミが死んだ」ことしか分からない状態にはなりません。
> どの敵にとどめを刺されたかが研究データに残ります。

### シナリオ 13：防具による軽減（任意）

1. オオカミに Wolf Armor を装備させる（`/item replace entity @e[type=wolf,limit=1] armor.body with minecraft:wolf_armor`）。
2. ゾンビに攻撃させる。
3. `damage.jsonl` の `healthBefore` と `healthAfter` の差が `actualDamage` と一致し、
   **攻撃の威力より小さい**ことを確認する。
4. 装備なしの個体と比べ、同じ攻撃でも `actualDamage` が違うことを確認する。

> これが README の `EFFECTIVE_HEALTH_LOSS` の定義どおりであることの確認です。

### シナリオ 14：救援後の再被弾

1. Adaptive のオオカミをテイムし、`/summon zombie ~ ~ ~5` でゾンビを出す。
2. **ゾンビにオオカミを攻撃させる**（1 回被弾させる）。
3. **自分でそのゾンビを 1 回攻撃する**（倒しきらない）。
4. **ゾンビにもう一度オオカミを攻撃させる**。
5. ゾンビを倒す（誰が倒してもよい）。
6. **成功時**：`events.jsonl` に `"eventType":"player_protected_wolf"` が 1 件出る。

> ここが落ちると、救援後も戦闘が続いた場合——つまり劣勢で危機的だった戦闘ほど——
> 救援の記録が消えます。Phase 13.4 以前はこの系列で不成立でした。

### シナリオ 15：介入の順序

1. **先に自分がゾンビを攻撃する**（オオカミはまだ無傷）。
2. その後、ゾンビにオオカミを攻撃させる。
3. ゾンビを倒す。
4. **成功時**：`player_protected_wolf` は**出ない**。代わりに
   `player_and_wolf_combat` が出る（オオカミも参戦していた場合）。

> 先に殴っていた敵に後からオオカミが噛まれただけでは、救援として発生した攻撃ではありません。

### シナリオ 16：ログの匿名化

1. 自分で何かにダメージを与える（ゾンビを殴る、弓で撃つ、どちらでも）。
2. `damage.jsonl` をテキストエディタで開く。
3. 自分のプレイヤー名で検索する → **0 件**であること。
4. 自分の UUID（`/data get entity @s UUID` で確認できます）で検索する → **0 件**。
5. UUID の先頭 8 文字で検索する → **0 件**。
6. `attackerType` が `owner` の行の `attackerId` が `subject-` で始まること。

### シナリオ 6：設定した実験条件が適用されるか

1. Minecraft を終了する。
2. `config/aiwolf.properties` を開き、`defaultCondition=static` に書き換えて保存する。
3. Minecraft を起動する。
4. ログ（`logs/latest.log`）で `Experiment metadata applied:` の行を探す。
   **成功時**：`configured default condition: static` と出る。
5. **新しい**オオカミをテイムし、`/wolfai status` を実行する。
   **成功時**：condition が `static` と表示される。
6. 撫でる・餌を与えるを 20 回以上繰り返しても、記憶件数が 0 のままで
   選好が初期値のままであることを確認する。
7. それでも同じ 8 種類の行動が出ることを確認する（バニラのオオカミにはならない）。

> 実験を始める前に必ずこれを確認してください。条件の取り違えは、
> そのデータを丸ごと使えなくします。**既存のオオカミの条件は変わりません。**
> `/wolfai mode` も既存個体には効かず、これ以降に作られる個体の既定条件を変えるだけです。

---

## 15. 禁止事項（開発ルール）

* 毎 tick の LLM API 呼び出し
* AI に Java コード文字列を実行させる
* API キーのコード直書き
* UUID 以外での個体識別
* `personality = BRAVE` のような固定人格の付与
* プレイヤーごとに手作業で異なる初期値を設定する
* 全 tick を研究ログへ保存する
* 巨大な God class の作成
* Minecraft / Fabric バージョンの無断変更
* エラーを放置したまま次のステップへ進む
* **失敗するテストをコメントアウトまたは削除して通す**
* LLM API / 外部チャットモデルへの接続、会話機能の追加
* 深層強化学習・ニューラルネット・埋め込みベクトルの導入（再現性が失われる）
* 固定の participant identifier を Git へ置く（`aiwolf.properties` は `.gitignore` 済み）
* プレイヤー名の保存

---

## License

MIT
