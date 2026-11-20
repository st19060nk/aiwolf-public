# デモ動画の撮影手順（Research Demo Mode）

教授への研究説明用に、1〜2 分の動画で

> 最初は同じ状態の AI ペットでも、プレイヤーとの経験によって行動傾向が変化する

ことを見せるための手順書です。

**この手順は研究パラメータを一切変えません。** Demo Mode が追加するのは
表示・リセット・保存と復元・学習の一時停止だけで、行動の計算式・重み・学習率・
Softmax 温度・閾値はすべて本実験と同じです。行動を直接決めるコマンドも、
性格を設定するコマンドもありません。**変化はすべて実際の経験から作ります。**

---

## 0. 共通の準備

### 撮影用ワールドを分ける

Demo Mode 中のログは `aiwolf-research/demo/` に分かれますが、**個体の状態そのもの**
（記憶・選好など）はワールドのセーブに残ります。本実験のワールドでは撮影しないでください。

```
新規ワールド（サバイバル、難易度ノーマル以上、チート有効）
/time set day
/gamerule doDaylightCycle false      ← 夜は行動スコアの文脈が変わるので、撮影中は昼に固定
/give @s minecraft:bone 16
/give @s minecraft:name_tag 4
/give @s minecraft:cooked_beef 32
/give @s minecraft:iron_sword
```

敵は `husk`（ハスク）を使うと昼でも燃えません。

```
/summon minecraft:husk ~6 ~ ~
```

### Demo Mode と表示

```
/wolfai demo on               ← ログが demo/ へ分かれ、各行に demoMode=true が付く
/wolfai select                ← オオカミを見ながら実行すると、その個体を対象に固定
/wolfai debug basic           ← 画面右にサイドバー（detailed で詳細）
```

* **名札で名前を付ける**と、表示が `A (WOLF-1a2b3c)` のようになり見分けやすくなります。
  画面に生の UUID は出ません。
* サイドバーは**自分にだけ**見えます。サーバの scoreboard には登録しません。
* `select` しない場合は「見ているオオカミ」が対象です（目を離しても 3 秒は保持）。
* サーバを再起動すると Demo Mode は必ず OFF に戻ります。

### 表示の読み方

| 行 | 意味 |
|---|---|
| `Behavior: FOLLOW_CLOSE 42%` | いま実行中の行動と、それが選ばれた確率 |
| `Trust / Attach / Fear / Curious` | 内部状態（0〜1） |
| `Memory: PLAYER_PROTECTED_WOLF (+2)` | 直近の決定で**実際に参照された**記憶のうち最も強いもの（ほか 2 件） |
| `Location fear 0.36` | いまいる場所の恐怖値（空間記憶。0.30 以上で AVOID_LOCATION が選択肢に入る） |
| `Top scores (score / P)` | detailed のみ。上位 4 行動のスコアと選択確率 |
| `Decision #123` | detailed のみ。決定の通し番号（研究ログと突き合わせ可能） |

**動画で「行動が変わった」と見せるときは、行動そのものより `P`（選択確率）を映す**のが確実です。
行動は Softmax で確率的に選ばれるため、30 秒（約 15 回の決定）では偶然に左右されます。
確率は内部状態と記憶から決定論的に決まるので、Before / After の差がぶれません。

### 経験イベントの起こし方（実装上の発火条件）

| イベント | 操作 | 注意 |
|---|---|---|
| `pet` | **しゃがんで素手で右クリック**（ハートが出る） | 10 秒のクールダウン。連打は無効 |
| `feed` | 肉を持って右クリック | **体力が満タンでないと記録されない**（バニラが餌を受け付けないため） |
| `wolf_damaged` 系 | 敵に攻撃させる | 被弾は**決定ごと（2 秒）に 1 件**へまとめられる。体力 40% 未満で `wolf_low_health`、15% 未満で `near_death` |
| `player_protected_wolf` | オオカミが被弾した**後で**飼い主がその敵を攻撃し、その敵が倒される | オオカミが先に被弾していることが条件。戦闘終了後の次の決定で記録 |
| `defend_success` | オオカミが飼い主を守って敵を倒す | |
| `monster_encounter` | 半径 16 ブロック以内に敵が現れる | 「いなかった → 現れた」の変化で 1 回 |
| `safe_solo_exploration` | オオカミが飼い主から 12 ブロック以上離れて EXPLORE_NEARBY を 5 回連続（約 10 秒）続ける | 被弾するとカウントがリセット |
| `long_separation` / `reunion` | 飼い主が 48 ブロック以上離れて 2 分 → 戻る | |
| `new_biome` | 初めてのバイオームへ入る | 個体ごとに 1 バイオーム 1 回だけ |

**座っている・リードに繋がれているオオカミは AI が判断しません**（出来事も検出されません。撫でと餌は記録されます）。

### 便利なコマンド

```
/wolfai inspect               ← 対象個体の状態・選好・記憶の内訳・空間記憶・行動をチャットに出す
/wolfai memory spatial        ← 現在地と近くの危険な場所（座標付き）
/wolfai snapshot save before  ← Before / After の保存（Demo Mode 中か実験セッション中）
/wolfai snapshot restore before
/wolfai snapshot list
/wolfai demo reset            ← 研究初期状態へ戻す（Demo Mode 中のみ）
/wolfai signature A_after     ← 標準テストで行動分布を数値化（個体の状態は変えない）
/wolfai summary               ← 直近 1 分 / 5 分 / セッションの実際の行動割合と飼い主との平均距離
/wolfai summary reset         ← サマリーの記録だけを消して新しいセッションを始める（個体と研究ログは変わらない）
/wolfai demo scenario bonding|danger|spatial|individuality
                              ← 撮影手順をチャットに出し、対象個体の進み具合（記憶の件数など）を表示
```

`scenario` は**手順を表示するだけ**で、出来事を起こしたり結果を作ったりはしません。

### 2 つの数値の違い：選択確率（サイドバー）と行動割合（summary）

| | サイドバーの `P` | `/wolfai summary` の割合 |
|---|---|---|
| 何か | いまの状態・記憶から計算した、次の行動の**選択確率** | 実際に**行動していた時間の割合** |
| 揺らぎ | なし（状態が同じなら同じ値） | あり（Softmax の抽選結果。直近 1 分は約 30 決定で、各決定が独立なら p = 50 % のとき標準偏差 約 9 pt。実際は同じ行動が続きやすいので、これより大きくぶれる） |
| 向いている使い方 | Before / After の「傾向の変化」を正確に示す | 「実際にこれだけ一緒にいた」を示す。5 分・セッションの列が安定 |

summary の記録は**メモリ上だけ**にあり、サーバを再起動すると消えます。
`snapshot restore` や `demo reset` をしても summary の記録は残るので、
Before / After を分けて集計したいときは、撮影区間の始めに `/wolfai summary reset` を実行してください。

---

## どのくらいの経験で、どのくらい変わるか（正直な見積もり）

以下は、現在の研究パラメータのまま、**理想化した出来事列をエージェントへ直接与えて**
計算した値です（ADAPTIVE 条件、τ = 0.60）。条件・手順・全データ・グラフは実験ノート
[`docs/lab-notes/2026-11-14_demo-effect-size.md`](lab-notes/2026-11-14_demo-effect-size.md) にあり、
`./gradlew demoEstimate` で再現できます。実機では出来事の発火頻度と Softmax の乱数で前後します。

数値は選択確率 [%]（Demo D のみ標準テストの出現割合 [%]、5 セッションの平均）。

| デモ | 経験 | 実時間の目安 | 変化 |
|---|---|---|---|
| A | 撫で 6 回 + 餌 3 回 | 約 1.5 分 | APPROACH_PLAYER 13.0 → 28.3、EXPLORE 58.8 → 48.5 |
| A | ＋救援 1 回 | ＋1 戦闘 | APPROACH_PLAYER 36.8 |
| A | ＋撫で 24 回 | さらに約 4 分 | APPROACH_PLAYER 60.4、EXPLORE 21.6 |
| A | その 30 分後 | | APPROACH_PLAYER 59.8（ほぼ残る） |
| B | 敵 1 体のそばで（Before） | | RETREAT 1.4、FOLLOW_CLOSE 4.1、EXPLORE 40.8、DEFEND 29.7 |
| B | 被弾 3 件 + 救援 1 回の 1 分後 | 1 戦闘 | RETREAT 16.8、FOLLOW_CLOSE 12.4、EXPLORE 10.2、DEFEND 27.3 |
| C | 同じ場所で被弾 1 / 2 / 3 / 4 件 | 2 秒以上あけて | 場所の恐怖 0.126 / 0.229 / **0.314** / 0.384（0.30 で危険判定） |
| C | 危険な洞窟に戻ったとき（2 分後） | | AVOID_LOCATION **38.7**、EXPLORE 4.9 |
| C | 別の洞窟（初めての場所） | | AVOID_LOCATION は選択肢にない（0.0）、EXPLORE 11.8 |
| D | 各 10 分の育成後、標準テスト | 各 10 分 | A: APPROACH 63.7・平均距離 2.0 blocks / B: EXPLORE 99.4・平均距離 39.0 blocks / 未育成: EXPLORE 53.4・平均距離 11.9 blocks |

**撮影時間について正直に書いておきます：**

* **Demo A** は撫でに 10 秒のクールダウンがあるため、はっきりした変化には **1.5〜5 分の交流**が要ります。
  30 秒〜1 分の動画にするには、交流の部分を早送り・カットで編集してください。
* **Demo C** は、同じ 24×24×24 ブロックの領域内で **2 秒以上あけて 3 回以上**被弾させる必要があります。
* **同じ場所で救援すると、その場所の恐怖は下がります**（救援は快い出来事なので、
  上の例では 0.450 → 0.355）。危険の印象を強く残したいなら、救援は少し離れた場所で行ってください。
* **場所の恐怖は時間では減りません**（空間記憶には時間減衰がない設計です）。
  32 分後でも AVOID_LOCATION 30.9 % が残りました。
* Demo B の RETREAT や FOLLOW_CLOSE の増加は、エピソード記憶によるものなので
  **場所を問わず**出ます。「その場所に特有の」変化は AVOID_LOCATION（Demo C）です。
* **Demo D** は経験だけで個体差を作るため、**1 匹あたり 10 分程度の育成**が必要です（2 匹は並行して育てられます）。

---

## Demo A：飼い主との距離感の変化（目安：撮影 30〜60 秒、準備 2〜5 分）

### 準備

1. オオカミを 1 匹テイムし、名札で `A` と名付ける
2. `/wolfai demo on` → オオカミを見て `/wolfai select` → `/wolfai debug detailed`
3. `/wolfai demo reset`（研究初期状態から始める）
4. `/wolfai snapshot save before`

### Before 撮影（15〜20 秒）

* 平地を歩き回り、オオカミがついてくる様子を撮る
* サイドバーの `Top scores` で **EXPLORE が約 6 割（58.8 %）、APPROACH が約 1 割（13.0 %）**であることを映す

### Interaction（撮影しながら、編集で短縮）

* しゃがんで素手で右クリック（撫でる）を 10 秒ごとに繰り返す（6 回以上）
* 体力が減っているときに肉を与える（満タンだと記録されません。軽く敵に当てておくと確実）
* 一緒に歩く
* できれば Demo B の救援を 1 回入れる（APPROACH が大きく伸びます）

### After 撮影（15〜20 秒）

* Before と同じ場所・同じ歩き方で撮る
* `APPROACH` / `FOLLOW_CLOSE` の P が上がり、`EXPLORE` が下がっていることを映す
* `/wolfai snapshot save after` → `/wolfai inspect` で trust / attachment の変化（括弧内）を見せる
* Before の撮影前と After の撮影前にそれぞれ `/wolfai summary reset` しておき、撮影後に `/wolfai summary` の
  `last 5 min` 列（APPROACH_PLAYER・FOLLOW_CLOSE の割合と Mean Owner Distance）を並べると、実際の距離感の差を数字で示せます

撮り直しは `/wolfai snapshot restore before` で何度でもできます。

---

## Demo B：危険経験と救援（目安：撮影 45〜60 秒）

### 準備

1. Demo A と同様に対象を選び `/wolfai demo reset`、`/wolfai snapshot save before`
2. 鉄の剣を持つ

### Before 撮影

```
/summon minecraft:husk ~8 ~ ~
```

* 敵が現れたときのサイドバーを映す（**RETREAT 約 1%、DEFEND 約 3 割、EXPLORE 約 4 割**）
* 撮ったら自分で敵を倒し、`/wolfai snapshot restore before` で経験をなかったことにする

### 経験を作る

1. `/summon minecraft:husk ~8 ~ ~` を出し、**オオカミが攻撃されるのを待つ**（2 秒以上あけて 2〜3 回被弾するまで）
2. 体力が減ったら**飼い主が剣でその敵を攻撃し、倒す**
3. 次の決定（約 2 秒後）で `player_protected_wolf` が記録される。
   `/wolfai inspect` の `Memory:` に `player_protected_wolf` が出れば成功
4. 1 分ほど一緒に過ごす（fear / stress が一時的に高い状態がやや落ち着く）

### After 撮影

```
/summon minecraft:husk ~8 ~ ~
```

* 同じように敵を出し、サイドバーで **RETREAT・FOLLOW_CLOSE の P が上がり、EXPLORE が下がる**ことを映す
* `Memory: PLAYER_PROTECTED_WOLF` や `WOLF_DAMAGED` が参照されている様子が「理由」として映る

---

## Demo C：危険な場所を覚えている（Video 1・最優先）

### 場所の選び方

* 地表から入れる**洞窟の一室**か、地下を掘った小部屋（目印を置く）
* 空間記憶は **24×24×24 ブロック**の立方体単位です。**高さ方向も区切られる**ので、
  被弾させるときと戻るときで同じ高さの範囲にいるようにしてください
* 撮影後の比較用に、**似た別の洞窟**（120 ブロック以上離れた場所）も用意すると効果的です

### 手順

1. `/wolfai demo reset` → `/wolfai snapshot save before`
2. `/wolfai debug detailed`
3. 洞窟の部屋で `/summon minecraft:husk ~6 ~ ~` を出し、**2 秒以上あけて 3〜4 回被弾させる**
   * `/wolfai memory spatial` で `Fear` が **0.30 を超え**、
     `危険な場所として記憶` と出るまで続ける
4. 飼い主が敵を倒す（**救援は部屋の外か入口付近**で行うと、部屋の恐怖が下がりにくい）
5. **一度その場所から離れる**：50 ブロック以上（領域 2 つ以上）離れ、30 秒〜1 分過ごす。
   `/wolfai memory spatial` の `Current Region` が `経験はまだありません` になっていれば離れています
6. `/wolfai snapshot save after`

### 撮影（Video 1 の構成）

| 時間 | 映すもの |
|---|---|
| 0:00〜0:15 | 洞窟で攻撃される（`Memory: WOLF_DAMAGED`、`Loc fear` が上がっていく） |
| 0:15〜0:25 | 飼い主が助ける（`PLAYER_PROTECTED_WOLF`） |
| 0:25〜0:35 | 離れる（`Loc fear` が 0 に戻る） |
| 0:35〜0:55 | 同じ洞窟に戻る：アクションバーに **`Spatial memory activated  Fear = 0.xx`**、サイドバーに **AVOID_LOCATION** が現れる |
| 0:55〜1:10 | （任意）別の洞窟では AVOID_LOCATION が出ないことを見せる |

確認する Debug 項目：`Memory`（参照された記憶）、`Loc fear`（空間記憶）、`Behavior` と `Top scores` の AVOID_LOCATION。

**比較用の Before 個体：**`/wolfai snapshot restore before` で同じ洞窟に連れて行くと、
AVOID_LOCATION が選択肢にない状態（約 6 割が EXPLORE）を撮れます。

---

## Demo D：同じ初期状態から異なる個体差（Video 2・最優先）

### 2 匹の用意（同じ Initial State）

```
/summon minecraft:wolf ~3 ~ ~
/summon minecraft:wolf ~-3 ~ ~
```

1. 2 匹ともテイムし、名札で `A` と `B` を付ける
2. `/wolfai demo on`
3. A を見て `/wolfai select` → `/wolfai demo reset` → `/wolfai snapshot save initial` → `/wolfai inspect`
4. B を見て `/wolfai select` → `/wolfai demo reset` → `/wolfai snapshot save initial` → `/wolfai inspect`
5. 2 つの `inspect` で **State と Preference がすべて同じ値**であることを確認する（動画の冒頭に使える）

アルゴリズム・行動セット・研究パラメータは 2 匹で完全に共通です。
違うのは個体固有の乱数シードだけで、これは行動の傾向ではなく「どの回にどれが出るか」だけに影響します。

### 育成（それぞれ約 10 分。2 匹を並行して育てられます）

**2 匹は 32 ブロック以上離してください。** 近くで戦闘すると、両方に同じ出来事が記録されます。

**Wolf A：飼い主中心の経験**

* そばで撫でる（10 秒ごと）・体力が減ったら餌
* 一緒に歩く
* 3 分に 1 回程度、敵を出して A を救援する（A が被弾 → 飼い主が倒す）

**Wolf B：探索中心の経験**

* 撫でない・餌をやらない・**座らせない**
* 飼い主から 12〜40 ブロック離れた場所で自由に探索させる
  （A を育てている場所から 30〜40 ブロック離しておくと、A の育成と同時に B が単独探索を重ねます）
* 飼い主が 48 ブロック以上離れないようにする（離れると分離の出来事が入ります）
* `/wolfai inspect` の Memory に `safe_solo_exploration` が増えていけば順調です

途中経過を `/wolfai snapshot save trained` で保存しておくと、評価を何度でも撮り直せます。

### Evaluation Arena

1. 平らで敵のいない場所に柵で 15×15 ブロック程度の囲いを作り、2 匹と飼い主が入る
2. `/wolfai evaluation start`
   * サイドバーとアクションバーに `[EVALUATION]` が出る
   * **学習（記憶の追加・空間記憶・選好・長期の内部状態）が止まり、行動選択はそのまま動く**
   * fear / stress だけは、その場の反応として STATIC 条件と同じ規則で動きます
3. 評価の開始時に A と B それぞれで `/wolfai summary reset` を実行する
4. 飼い主はゆっくり歩き回る。A と B それぞれを `select` してサイドバーを切り替えながら撮る。
   5 分ほど経ったら、それぞれで `/wolfai summary` を実行し、`last 5 min` の割合と平均距離を並べる
5. 数値での比較：A の隣で `/wolfai signature A_eval`、B の隣で `/wolfai signature B_eval`
   （標準テストは個体の状態を変えず、ワールドの状況にも左右されない同一条件の測定です。
   最も近い自分の飼い犬が測定対象なので、測りたい個体の隣で実行してください）
6. `/wolfai evaluation stop`

### Video 2 の構成

| 時間 | 映すもの |
|---|---|
| 0:00〜0:15 | 2 匹の `inspect`：State・Preference が同一 |
| 0:15〜0:40 | 育成（早送り）：A は撫で・救援、B は単独探索 |
| 0:40〜1:20 | Evaluation Arena：A は飼い主のそば（APPROACH / FOLLOW）、B は離れて探索（EXPLORE） |
| 1:20〜1:40 | `summary` と `signature` の結果を並べる（行動の割合と平均距離） |

---

## 撮影後

```
/wolfai demo off
```

* 評価モードも同時に終わり、ログは通常の出力先へ戻ります
* デモ中のログは `<ワールド>/aiwolf-research/demo/*.jsonl`、
  スナップショットは `<ワールド>/aiwolf-research/demo/snapshots/` にあります。
  本実験の解析スクリプトに渡さないでください（各行に `"demoMode":true` が付いています）

## 制約

* サイドバーは Minecraft の仕様で 15 行までです。detailed はこれに収まるよう要約しています
* プレイヤーが自分で別のサイドバー表示を使っていた場合、デバッグ表示を消したあともそれは消えたままです
  （`/scoreboard objectives setdisplay sidebar <名前>` で戻せます）
* 評価モード中も「訪問済みバイオーム」「撫でのクールダウン」などの観測器の記録は進みます。
  評価中に初めて入ったバイオームは、学習しないまま「訪問済み」になります
* `snapshot restore` は進行中の戦闘を復元しません（ワールド保存と同じ扱い）。戦闘が終わってから使ってください
