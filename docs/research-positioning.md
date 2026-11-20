# Research Positioning — AI Wolf Study 2（記憶の行動的表出）

作成: 2026-11-16 / 元資料: `aiwolf_related_work_for_claude.md`

> 中心テーマ: **AIペットの記憶を、人が行動から感じ取れるか**

### 検証状況の凡例

| 記号 | 意味 |
|---|---|
| ✅ | 今回ウェブ上で書誌情報・要旨を確認した |
| 📖 | よく知られた文献で書誌は確かだが、今回は原文を開いていない |
| ⚠️ | 今回の検索では見つからなかった。引用前に IEEE Xplore 等で必ず確認すること |

元メモからの修正点:
- **Allison et al. の2本目**: 「DiGRA 2017」は確認できなかった。確認できたのは *Transactions of the Digital Games Research Association* 4(2) 掲載の "How Players Speak to an Intelligent Game Character Using Natural Language Messages" で、同じ Wizard-of-Oz 研究（N=18）を使っている。"Player expectations of a learning AI companion in Minecraft" を引く前に、DiGRA 2017 の proceedings で実在を確認すること。
- **TMR (Nakamura et al., RO-MAN 2026)**: ⚠️ 見つからなかった。存在しないとまでは言えないが、確認できるまで引用しないこと。
- **RECALLbot**: ✅ 書誌（CHI '26, Article 795, DOI 10.1145/3772318.3790714）は他論文の引用から確認できた。要旨（N=40、2週間、We Memory）は確認できていないので、ACM DL で原文を見ること。
- **Yang et al. 2025**: ✅ 正式タイトルの末尾に "A Stimulus–Organism–Response Perspective" がつく。対象ロボットは MOCCA で、PLS-SEM による**質問紙調査**であり、記憶の ON/OFF を操作した実験ではない。

---

## 1. Current Research Question

**RQ1（主）**: 過去の共有経験に基づいて非言語行動が変わることで、ユーザはAIペットが「その経験を覚えている」と感じるか。

**RQ2（副）**: 記憶をどの行動で表すと（場所の回避、飼い主への接近、ためらい、退避、探索の抑制）、最もよく「覚えている」と読み取られるか。

最初の研究では RQ3〜RQ5（記憶の種類による差、完全想起か確率的変化か、愛着や信頼への波及）は扱わない。ただし RQ4 については、Pilot の副次的な観察として得るものがある（§7）。

従属変数として操作的に定義するのは **perceived remembering（覚えている感）** である。attachment、trust、animacy は二次的な指標にとどめる。

---

## 2. Most Relevant Prior Work

### 2.1 Directly related（研究質問がかなり近い）

Memory を持つエージェントというだけでは選んでいない。「**記憶の有無を操作し、人の知覚を測った**」ことを基準にした。

| # | 文献 | 確認 | 近い点 | 遠い点 |
|---|---|---|---|---|
| D1 | Aschenbrenner et al. 2026, *Not Forgotten*, RO-MAN（arXiv 2607.24190） | ✅ | memory ON/OFF、within-subjects、動画提示、N=43。perceived sociability の d=0.60 で、効果が最も大きかったのは trustworthiness（d=0.62）と warmth（d=0.56） | 記憶は**発話**で表出される。人型の頭部ロボット |
| D2 | Richards & Bransky 2014, *ForgetMeNot*, IJHCS 72(5) | ✅ | 想起の仕方（正しい想起・忘却・誤った想起）が believability、trust、「自然な記憶」の評価を変える | 会話内容の想起。不動産エージェント |
| D3 | Jiang et al. 2026, *RECALLbot*, CHI | ✅書誌のみ | We Memory（共有経験の記憶）という単位 | チャットボット、言語による開示 |
| D4 | Xie et al. 2025, *iPET*, ACL Demo | ✅ | virtual pet。memory module がエージェントとユーザ双方の経験を記録し、行動生成にも使う | system demo で、記憶知覚のユーザ評価はない（要確認）。対話が中心 |

### 2.2 Adjacent（技術・評価法・応用文脈の一部が近い）

| 領域 | 文献 | 確認 | 何が近いか |
|---|---|---|---|
| Memory | Kasap & Magnenat-Thalmann 2012, Vis. Comput. 28 | 📖 | 長期関係における remembering の役割（対話計画） |
| Memory | Park et al. 2023, Generative Agents, UIST | 📖 | recency / importance / relevance による検索を行動計画に使う。AI Wolf の event → retrieval → scoring の技術的な背景 |
| Memory | Kang et al. 2024, Nadine, CAVW | 📖 | episodic memory を感情と行動へ反映するロボット |
| Memory | Yang, Cho & Kim 2025, Appl. Sci. 15(10) | ✅ | episodic memory ロボットの trust と parasocial interaction（質問紙、SEM） |
| Memory | Ho, Dautenhahn & Nehaniv 2008, *Connection Science* 20(1) | ✅ | 仮想環境のエージェントに autobiographic memory を持たせると**行動が適応的に変わる**。ただし評価はエージェントの性能で、人の知覚は測っていない |
| Pet | Dodd, Fowler & Lottridge 2025, Entertain. Comput. 54 | ✅ | virtual pet 研究45本の narrative review（オープンアクセス）。引用をたどる起点になる |
| Pet / Nonverbal | Nam et al. 2025, *AR Fitness Dog*, TVCG 31(5) | ✅ | **発話のない犬の行動だけ**で companionship と social presence が変わる |
| Pet | Blumberg et al. 2002, *Integrated learning for interactive synthetic characters*, SIGGRAPH（Dobie） | ✅ | クリッカートレーニングで学習する仮想の犬。**学習が行動に表れる動物型キャラクタ**の古典 |
| Pet / Spatial | Isla & Blumberg 2002, *Object Persistence for Synthetic Creatures*, AAMAS（Duncan） | ✅ | 仮想のテリアが確率的占有マップで物の位置を予測する。**空間記憶を行動に出す**系譜 |
| Pet | Tomlinson & Blumberg 2002, *AlphaWolf*, AAMAS/SIGGRAPH ET | 📖 | 社会関係を記憶し、それに応じて振る舞う**仮想のオオカミ**。題材がほぼ同じなので必ず引用する |
| Nonverbal | Wang & Ruiz 2021, IJHCI 37(17) | 📖 | 非言語行動が social perception に影響することのレビュー |
| Game | Allison, Luger & Hofmann 2017, CHI EA ／ ToDiGRA 4(2) | ✅ | Minecraft 内で学習する companion（WoZ、N=18）。プレイヤーは行動を通して「教えよう」とした |
| Game | Yin & Xiao 2024, *Press A or Wave*, CHI PLAY（DOI 10.1145/3677098） | ✅要旨のみ | VR の NPC に対する非言語インタラクションへの期待（N=18） |
| Perception | 博物館ロボットの personalization cues 研究（PMC12656183） | ✅要旨のみ | 適応的な行動変化は、**理由や意図を推測できるときに好意的に解釈される**。行動からの帰属という本研究の論点に直結する |

---

## 3. Comparison Table

| 研究 | Memory 実装 | ユーザへの表出 | Human study | 評価指標 | 主な結果 | AI Wolf との違い |
|---|---|---|---|---|---|---|
| D1 Not Forgotten | ベクトル検索 + LLM 対話 | 発話で過去に言及 | ✅ 動画、within、N=43 | HRIES | sociability ↑（d=0.60） | 表出が**言語**。共有経験ではなくユーザ情報 |
| D2 ForgetMeNot | シナリオ化した想起条件 | 発話による想起・忘却 | ✅ 3 セッション | believability、trust、enjoyment | 誤った想起は最悪、忘却は自然 | 言語。**「どう表出するか」が知覚を変える**という示唆は流用できる |
| D3 RECALLbot | Me / We Memory + reciprocal disclosure | 発話による開示 | ✅（N=40、2週間：要確認） | social identity、trust など | memory 条件で向上（要確認） | 言語。We Memory の概念は流用できる |
| D4 iPET | 経験記録 + コサイン類似度検索 | 対話と生成活動 | ❓ demo | ❓ | — | 対話中心で、空間文脈なし |
| AR Fitness Dog | なし（模倣） | 行動のみ | ✅ 3 条件 | companionship、social presence | 模倣する犬 > ランダム／なし | **記憶ではない**。行動だけで知覚が動くことの傍証 |
| Ho et al. 2008 | autobiographic memory | 行動（適応） | ✗ | 生存などの性能 | 記憶 → 適応的行動 | **人の知覚を測っていない** |
| AlphaWolf / Dobie / Duncan | 学習・社会記憶・空間記憶 | 行動 | 展示・デモ中心 | — | — | 「覚えている感」を**統制実験で測っていない**（要確認） |
| Allison et al. | WoZ による学習 | 行動とテキスト | ✅ 観察、N=18 | 定性分析 | プレイヤーは教えようとした | 記憶の知覚が主題ではなく、ペットでもない |
| **AI Wolf（本研究）** | event → memory → retrieval → behavior score | **非言語行動のみ** | 予定（動画、ON/OFF） | perceived remembering ほか | — | — |

---

## 4. What Prior Work Has Established

1. **想起する記憶はプラスに働く**。言語で過去に言及する記憶は、sociability、trust、warmth、enjoyment を上げる（D1、D2、D3）。
2. **表出の質が重要**。誤った想起は「覚えていない」より悪く、部分的な忘却の方が自然に見えることがある（D2）。
3. **言語がなくても行動だけで社会的な知覚は動く**（AR Fitness Dog、Wang & Ruiz）。
4. **動物型キャラクタに学習や記憶を持たせ、行動に反映させる技術は20年以上前からある**（Blumberg らの Dobie、Duncan、AlphaWolf）。技術そのものは新しくない。
5. **適応的な行動変化は、人が理由を推測できるときに肯定的に解釈される**（博物館ロボット研究）。

## 5. What Remains Unclear

1. 言語を一切使わずに、行動の変化だけで人が「**記憶**」を帰属するのか。「性格」「気まぐれ」「現在の状態（怯え）」として読むのではないか。
2. その帰属は、**特定の共有経験**に結びついているのか（「あの洞窟を覚えている」）、それとも汎化した状態として読まれるのか（「臆病になった」）。
3. どの行動チャネル（場所の回避、飼い主への接近、ためらい）が記憶の手がかりとして効くのか。
4. 確率的に弱く出る変化でも記憶として読まれるのか、明確な変化が必要なのか（ForgetMeNot の行動版の問い）。

## 6. Candidate Research Gap

### 日本語版

> 過去のインタラクションを記憶するエージェントは、社会性・信頼・楽しさの評価を高めることが示されてきた [D1, D2, D3]。しかし、これらの研究の多くは記憶を**発話による過去への言及**として表出させており、記憶そのものよりも「言葉で思い出してみせること」の効果を測定している可能性がある。一方、virtual pet や動物型キャラクタの研究では、非言語行動が companionship や social presence を変えること [Nam 2025]、また学習や記憶を行動に反映するキャラクタが実装可能であること [Blumberg 2002; Isla & Blumberg 2002; Tomlinson & Blumberg 2002] が示されてきた。ただし、行動に表れた記憶を**人が記憶として読み取るかどうか**は、我々の調べた範囲では統制された形で検証されていない。そこで本研究では、Minecraft 上の非言語的なオオカミ型 AI ペットを対象に、同一の個体状態から記憶の利用のみを ON/OFF し、過去の共有経験（危険な場所、飼い主による救助）に基づく行動変化が、観察者に「覚えている」という知覚を生むかを検証する。

### English

> Prior work has shown that agents which remember past interactions are perceived as more sociable, trustworthy, and enjoyable [D1, D2, D3]. However, in most of these studies memory is expressed through **verbal references to the past**, so their effects may stem from the act of verbally recalling rather than from memory per se. Research on virtual pets and animal-like characters has shown that nonverbal behavior alone can shape companionship and social presence [Nam et al. 2025], and that learning and memory can be embedded in a character's behavior [Blumberg et al. 2002; Isla & Blumberg 2002; Tomlinson & Blumberg 2002]. Yet, to our knowledge, whether people **attribute memory to a non-verbal pet from behavior alone**—and specifically memory of an experience they shared with it—has not been examined under controlled conditions. To address this gap, we isolate memory retrieval (ON vs. OFF) in a wolf-like AI pet in Minecraft, holding the agent's internal state constant, and test whether behavior shaped by a shared past experience (a dangerous place; being rescued by the owner) leads observers to perceive that the pet remembers.

**書き方の注意**: "to our knowledge" を必ずつけ、"first" とは書かない。Novelty の主張は「技術」ではなく「**行動のみから記憶が帰属されるかの統制検証**」に置く（§10）。

---

## 7. Recommended First Experiment（Pilot）

目的は、大規模な検定ではなく「覚えている感が生まれそうか」と、刺激の作り方に問題がないかを確認すること。

### 7.1 デザイン：Memory × Location の 2×2（Scenario 1）

元メモの ON/OFF だけでは、**RETREAT 等の増加が「洞窟Aを覚えている」のか「怖い目にあって全体に臆病になった」のか区別できない**（元メモ §10 の指摘どおり）。そこで、場所条件を加える。

| | 経験した洞窟 A | 見た目を揃えた未経験の洞窟 B |
|---|---|---|
| **Memory ON**（STATE_MEMORY / FULL） | 予測：回避 ↑、接近 ↑ | 予測：変化は小さい |
| **Memory OFF**（STATE_ONLY） | 予測：状態による変化のみ | 予測：状態による変化のみ |

場所に特異的な記憶の証拠になるのは、**ON×A だけが突出する交互作用**である。

### 7.2 刺激の作り方（既存インフラで実装可能）

1. 1 個体に Scenario 1 の経験（洞窟 A でダメージを受けて退出）をさせる → `/wolfai snapshot`
2. 同じ snapshot から、`/wolfai mode` で ON/OFF を切り替え、洞窟 A と B に連れて行く場面を録画する
3. **行動は確率的なので、条件ごとに複数回試行する**（例: 各 10 試行）。動画に使う試行は事前に決めた規則で選ぶ（例: ランダム抽出、または Memory contribution が中央値の試行）。良い試行だけを選ぶと結果が無効になる
4. Behavior Score の component breakdown を全試行で記録しておく（§13 の分析用）

### 7.3 参加者への提示

- **まず「経験動画」**（洞窟 A で襲われる場面）を全員に見せる。共有経験を知らない人は「覚えている」を判断できないため
- 続けて「テスト動画」を提示する。within-subjects で、4 条件の順序はカウンターバランスする
- 動画の長さは 20〜30 秒で揃える

### 7.4 参加者

N = 10〜15。自分自身と、設計を知っている研究室メンバーは除く（`pilot-protocol.md` で述べたとおり、知っている人は被験者として無効）。

### 7.5 Scenario 2（owner rescue）は2回目

経験：襲われる → 飼い主が倒して助ける。テスト：似た脅威のもとで、**助けた飼い主**と**助けていない別のプレイヤー**のどちらに寄るかを見る（関係的記憶の統制）。マルチプレイヤーでの撮影が必要なので、Scenario 1 の後に回す。

---

## 8. Measurement Candidates

### 8.1 Primary（独自項目）— perceived remembering

既存の確立した尺度はないので、独自項目であることを明記し、Pilot で内的一貫性を確認する（7 件法）。

1. このオオカミは、以前の出来事を覚えているように感じた
2. 過去の経験が、今の行動に影響しているように見えた
3. このオオカミは、**この場所で**何があったかを知っているように見えた（場所特異性の項目）

### 8.2 Primary の補強（強く推奨）

- **自由記述**:「なぜこのオオカミはこう振る舞ったと思いますか？」を、**Likert 項目より先に**聞く。記憶への言及（「前に襲われたから」）があるかをコーディングする。誘導のない帰属なので、Likert よりも強い証拠になる
- **強制選択**: ON と OFF のペアを見せて「どちらが前の出来事を覚えていると思うか」

### 8.3 既存尺度（Secondary）

| 構成概念 | 尺度 | 備考 |
|---|---|---|
| Mind perception / memory | Gray, Gray & Wegner 2007, *Science*（Dimensions of Mind Perception） | agency の構成能力に **memory が含まれる**。単項目として借用でき、理論的な根拠にもなる |
| Animacy、likeability、perceived intelligence | Godspeed（Bartneck et al. 2009, IJSR） | 標準的だが、項目の妥当性への批判もある |
| Sociability、agency、animacy | HRIES（Spatola et al. 2021, IJSR） | D1 と同じ尺度なので比較できる |
| Social presence | Networked Minds（Harms & Biocca 2004） | 動画視聴では一部の項目が合わない |
| Believability | Gomes et al. 2013, ICIDS（character believability metrics） | 「行動に理由がある」「行動が自然」などの項目を流用できる |
| Attachment | LAPS / Brief-LAPS（7 項目） | 実在するペット用。Study 2 には時期尚早で、長期研究向け |
| Trust | — | 動物型ペットへの trust は概念が曖昧。Pilot では外してよい |

※ Gray 2007、Godspeed、HRIES、Networked Minds、Gomes 2013 は 📖（今回は原文未確認）。

---

## 9. Additional Papers to Read（追加 8 本）

| 優先 | 文献 | 理由 |
|---|---|---|
| A | Tomlinson & Blumberg 2002, *AlphaWolf: Social Learning, Emotion and Development in Autonomous Virtual Agents* | 記憶を持つ**仮想のオオカミ**。novelty の衝突を確認する最優先の文献 |
| A | Isla & Blumberg 2002, *Object Persistence for Synthetic Creatures*, AAMAS | 空間記憶を行動に出す仮想の犬 |
| A | Blumberg et al. 2002, *Integrated learning for interactive synthetic characters*, SIGGRAPH | 学習を行動に出す仮想の犬（Dobie） |
| A | Gray, Gray & Wegner 2007, *Dimensions of Mind Perception*, Science | 「記憶の帰属」を mind perception として理論づけられる |
| A | 博物館ロボットの personalization cues 研究（PMC12656183） | 行動変化から意図や理由を推論する帰属の研究 |
| B | Ho, Dautenhahn & Nehaniv 2008, Connection Science 20(1) | autobiographic memory → 行動適応 |
| B | Allison et al., ToDiGRA 4(2), *How Players Speak to an Intelligent Game Character* | Minecraft companion 研究の正式な journal 版 |
| B | Heider & Simmel 1944 ／ Scholl & Tremoulet 2000（perceptual causality & animacy） | 動きだけから意図や心的状態が知覚される古典。「行動から記憶を読む」の理論的な祖先 |

---

## 10. Risks / Possible Novelty Conflicts

| リスク | 内容 | 対処 |
|---|---|---|
| **古典との衝突** | Blumberg グループ（AlphaWolf、Dobie、Duncan）は「記憶や学習を行動に出す動物型キャラクタ」を既に実装している | Novelty を**技術ではなく、人の帰属の統制検証**に置く。これらは Related Work の主要な柱として正面から引用する |
| 商用ゲームの先行例 | Black & White のクリーチャー、Nintendogs、The Last Guardian など、学習するペット的キャラクタは多い | 「商用の実装はあるが、記憶知覚の統制評価はない」と書く。断定はしない |
| 未確認の文献 | TMR、DiGRA 2017、RECALLbot の要旨、iPET のユーザ評価の有無 | 投稿前に原文を確認する。確認できなければ引用しない |
| 帰属の代替解釈 | 観察者が記憶ではなく「性格」「今の怯え」として読む | 2×2（場所の統制）と自由記述のコーディングで切り分ける |
| 要求特性 | 「覚えているか」と聞くこと自体が記憶の帰属を誘導する | 自由記述を先に聞く。OFF 条件でもスコアが高ければ天井効果を疑う |
| 刺激の選択 | 確率的な行動から都合の良い試行を選ぶ恐れ | 選択規則を事前に固定し、試行ログを公開する |
| 体系的検索の不足 | 今回は WebSearch によるスポット確認のみ | ACM DL、IEEE Xplore、Scopus で §16 の検索語を使って系統的に検索し、件数を記録する（PRISMA 風） |

---

## Sources（今回確認したもの）

- [Not Forgotten (arXiv 2607.24190)](https://arxiv.org/pdf/2607.24190) / [HdM Stuttgart RO-MAN 2026](https://ai.hdm-stuttgart.de/news/2026/publications-at-ro-man-2026/)
- [ForgetMeNot (Macquarie)](https://researchers.mq.edu.au/en/publications/forgetmenot-what-and-how-users-expect-intelligent-virtual-agents-/)
- [RECALLbot の引用元 (arXiv 2609.24706)](https://arxiv.org/pdf/2609.24706)
- [iPET (ACL Anthology)](https://aclanthology.org/2025.acl-demo.40)
- [Purr-ogrammed Love (AUT)](https://openrepository.aut.ac.nz/items/94b6f5ef-e271-4b97-8330-5332a4501d0b)
- [AR Fitness Dog (IEEE Xplore)](https://ieeexplore.ieee.org/document/10919006)
- [Yang et al. 2025 (MDPI)](https://www.mdpi.com/2076-3417/15/10/5334)
- [Allison et al. CHI EA 2017 (MSR)](https://www.microsoft.com/en-us/research/publication/spontaneous-interactions-virtually-embodied-intelligent-assistant-minecraft/) / [ToDiGRA 版 (Edinburgh)](https://www.research.ed.ac.uk/en/publications/how-players-speak-to-an-intelligent-game-character-using-natural-/)
- [Dobie T. Coyote (MIT)](https://characters.media.mit.edu/projects/dobie.html) / [SIGGRAPH 2002 archive](https://history.siggraph.org/?p=118428)
- [Object Persistence for Synthetic Creatures (PDF)](https://characters.media.mit.edu/Papers/objectPersistence.pdf)
- [Ho, Dautenhahn & Nehaniv 2008 (Herts)](https://researchprofiles.herts.ac.uk/en/publications/computational-memory-architectures-for-autobiographic-agents-inte/)
- [Press A or Wave (ACM)](https://dl.acm.org/doi/abs/10.1145/3677098)
- [Museum robot personalization cues (PMC)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12656183/)
- [Brief-LAPS (Frontiers)](https://www.frontiersin.org/journals/veterinary-science/articles/10.3389/fvets.2025.1619187/full)
