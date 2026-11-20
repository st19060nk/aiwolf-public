# 研究用サーバの構築手順

被験者実験では、参加者の PC ではなく**サーバに MOD を入れる方式**を推奨します。
本 MOD はクライアント側の機能を一切使っていないため（`net.minecraft.client` への依存ゼロ、
Mixin もサーバ側で動く `MobEntity` のみ）、サーバだけに入れれば動作します。

| | 参加者PCに導入 | サーバに導入 |
|---|---|---|
| 参加者の準備 | Fabric の導入が必要 | **サーバアドレスを入れるだけ** |
| データ回収 | 参加者ごとに手作業 | **1 箇所に集まる** |
| MOD の更新 | 全員へ配り直し | サーバだけ差し替え |
| 条件の割り当て | 参加者ごとに設定 | サーバ側で一括管理 |

---

## 1. 必要なもの

* **JDK 21**（開発で入れたものと同じでよい）
* **Fabric Installer** — https://fabricmc.net/use/installer
* **Fabric API 0.102.0+1.21.1** — Modrinth または CurseForge
* **本 MOD の jar** — `./gradlew build` で `build/libs/aiwolf-research-0.1.0-phase1.jar`

バージョンは `gradle.properties` と一致させること。Minecraft 1.21.1 以外では動きません。

## 2. サーバの設置

1. 空のフォルダを作る（例 `C:\dev\aiwolf-server`）
2. Fabric Installer（jar）を実行し、**Server** タブを開く
3. Minecraft version に **1.21.1** を選ぶ
4. Output directory に手順 1 のフォルダを指定する
5. サーバ本体（vanilla server jar）も一緒に取得するオプションがあれば有効にする。
   なければ Minecraft 公式サイトから 1.21.1 の `server.jar` を同じフォルダへ置く
6. Install を実行する

実行すると、起動用の jar（`fabric-server-launch.jar` のような名前）が作られます。
画面に表示される実際のファイル名を確認してください。

## 3. 初回起動と EULA

```bat
java -Xmx4G -jar fabric-server-launch.jar nogui
```

初回は `eula.txt` を作って停止します。中身を `eula=true` に書き換えて、もう一度起動してください。

`start.bat` を作っておくと毎回の起動が楽です（本ディレクトリの `start.bat.template` を参照）。

## 4. MOD の配置

サーバフォルダに `mods` フォルダができているので、次の 2 つを入れます。

```
mods/
├── fabric-api-0.102.0+1.21.1.jar
└── aiwolf-research-0.1.0-phase1.jar
```

起動ログに `AI Wolf Research mod initialising` と
`Research logs will be written to ...` が出れば成功です。

## 5. 研究用の `server.properties`

```properties
# 敵性 MOB が湧かないと DEFEND_PLAYER / RETREAT のイベントが一切発生しない。
# peaceful にしてはならない。
difficulty=normal
spawn-monsters=true

# 参加者どうしの干渉を避ける
pvp=false

# 拠点づくりを妨げない
spawn-protection=0

# 正規アカウントで入ってもらう。UUID が安定し、匿名 ID も一貫する
online-mode=true

# 研究データはこのワールド名のフォルダに入る
level-name=study

motd=AI Wolf Research
max-players=8
view-distance=10
```

## 6. 実験者に operator 権限を与える

`/wolfai` は operator（レベル 2）を要求します。サーバコンソールで実行してください。

```
op <実験者のMinecraft名>
```

**参加者には operator を与えないでください。** 内部数値を見てしまうと
「性格値を見たから個性を判断した」という交絡が生じ、研究の妥当性が損なわれます。

## 7. 参加者の接続

同じ LAN なら、サーバ機のローカル IP（`ipconfig` で確認）を
マルチプレイの「サーバを追加」に入れるだけです。既定ポートは 25565 です。

インターネット越しに参加してもらう場合は、ルータのポート開放か
トンネリングサービスが必要になります。大学のネットワークでは
ポート開放が許可されていないことが多いので、**実験は LAN 内で行うのが現実的**です。

## 8. 研究データの場所

```
<サーバフォルダ>/<level-name>/aiwolf-research/
├── decisions.jsonl
├── events.jsonl
└── export-YYYYMMDD-HHMMSS/
    ├── wolves.json
    └── memories.jsonl
```

`/wolfai export` でスナップショットを取れます。継続記録の 2 ファイルは自動で溜まります。

---

## 実験設計上の注意

サーバ方式には、共有ワールドゆえの論点が 2 つあります。**実装ではなく設計の問題**なので、
プロトコルを決める段階で考えておく必要があります。

### 参加者どうしがお互いのオオカミを見てしまう

* **育成フェーズ**では干渉になります。他人のオオカミが視界に入ると、
  自分のオオカミの行動を観察する妨げになり、`PLAYER_DAMAGED_NEAR_WOLF` のような
  イベントも他人の行動で発生しかねません。
  → 参加者を 1 人ずつセッションに入れる、または十分に離れた区画を割り当てる
* **識別フェーズ**では逆に、複数のオオカミが同時に見える状態が必要です（仮説 H3 の測定そのもの）。
  → こちらは意図的に同席させる

育成と識別で必要な条件が逆になるので、フェーズごとにワールドを分けるか、
入室スケジュールで制御するのが確実です。

### 実験条件の割り当て

`/wolfai mode` は**新規個体に割り当てる既定条件をサーバ全体で切り替える**コマンドです。
参加者ごとに条件を変える被験者間デザインでは、参加者が入室するたびに実験者が
切り替える必要があり、割り当てミスが起きます。

参加者 ID から条件を決定論的に割り当てる設定ファイルを用意するのが本来の姿です。
現時点では未実装なので、**当面は 1 セッション 1 条件で運用**してください。
