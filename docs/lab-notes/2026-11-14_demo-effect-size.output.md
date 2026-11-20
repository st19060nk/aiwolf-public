# Demo effect-size estimate
PARAMETER_SET_VERSION=2  SOFTMAX_TEMPERATURE=0.60  experimentSeed=42

## Demo A  選択確率 [%]（文脈 = calm）
| 時点 | FOLLOW_CLOSE | FOLLOW_NORMAL | EXPLORE_NEARBY | APPROACH_PLAYER | DEFEND_PLAYER | RETREAT | AVOID_LOCATION | REST_NEAR_PLAYER |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A0 初期 | 3.7 | 21.6 | 58.8 | 13.0 | 0.0 | 1.4 | 0.0 | 1.5 |
| A1 撫で6+餌3 (経過 1.5 分) | 3.2 | 18.1 | 48.5 | 28.3 | 0.0 | 0.8 | 0.0 | 1.1 |
| A2 +救援1 | 4.9 | 16.0 | 40.8 | 36.8 | 0.0 | 0.6 | 0.0 | 0.9 |
| A3 +撫で24 (さらに 4 分) | 5.3 | 11.9 | 21.6 | 60.4 | 0.0 | 0.2 | 0.0 | 0.6 |
| A4 A3 の 30 分後 | 7.4 | 14.9 | 16.2 | 59.8 | 0.0 | 0.4 | 0.0 | 1.2 |
A3 state: WolfInternalState{trust=0.773, attachment=0.965, fear=0.295, stress=0.14, curiosity=0.5, autonomy=0.5, energy=0.864}
A3 preference: PreferenceVector{follow_close=0.092, follow_normal=0.0042, explore_nearby=-0.0293, approach_player=0.1254, defend_player=0.0, retreat=0.0, avoid_location=0.0, rest_near_player=0.0}

## Demo A 系列  撫での回数 → P(APPROACH_PLAYER) [%]（撫で 10 秒間隔のみ、文脈 = calm）
| 撫での回数 | 経過 [分] | P(APPROACH_PLAYER) [%] | P(EXPLORE_NEARBY) [%] | trust | attachment |
|---:|---:|---:|---:|---:|---:|
| 0 | 0.0 | 13.0 | 58.8 | 0.500 | 0.500 |
| 3 | 0.5 | 20.3 | 53.9 | 0.521 | 0.542 |
| 6 | 1.0 | 27.4 | 49.0 | 0.542 | 0.584 |
| 9 | 1.5 | 29.7 | 47.4 | 0.563 | 0.626 |
| 12 | 2.0 | 32.0 | 45.7 | 0.584 | 0.668 |
| 15 | 2.5 | 34.4 | 43.9 | 0.605 | 0.710 |
| 18 | 3.0 | 37.0 | 42.0 | 0.626 | 0.752 |
| 21 | 3.5 | 39.8 | 39.7 | 0.647 | 0.794 |
| 24 | 4.0 | 43.4 | 36.2 | 0.668 | 0.836 |
| 27 | 4.5 | 47.0 | 32.8 | 0.689 | 0.878 |
| 30 | 5.0 | 50.5 | 29.6 | 0.710 | 0.920 |

## Demo B  選択確率 [%]（文脈 = threat）
| 時点 | FOLLOW_CLOSE | FOLLOW_NORMAL | EXPLORE_NEARBY | APPROACH_PLAYER | DEFEND_PLAYER | RETREAT | AVOID_LOCATION | REST_NEAR_PLAYER |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| B0 初期 | 4.1 | 11.7 | 40.8 | 11.5 | 29.7 | 1.4 | 0.0 | 0.9 |
| B1 被弾3+救援1 の直後 | 12.6 | 11.4 | 9.9 | 20.2 | 26.9 | 17.5 | 0.0 | 1.5 |
| B2 B1 の 1 分後 | 12.4 | 11.6 | 10.2 | 20.3 | 27.3 | 16.8 | 0.0 | 1.5 |
| B3 B1 の 31 分後 | 8.6 | 13.2 | 17.8 | 20.5 | 30.0 | 7.8 | 0.0 | 2.1 |
B2 state: WolfInternalState{trust=0.545, attachment=0.527, fear=0.443, stress=0.323, curiosity=0.5, autonomy=0.5, energy=0.996}
B3 state: WolfInternalState{trust=0.545, attachment=0.527, fear=0.343, stress=0.237, curiosity=0.5, autonomy=0.5, energy=0.876}

## Demo C 系列  同じ場所での被弾回数 → 場所の実効恐怖値（被弾は 2 秒間隔）
| 被弾回数 | 実効恐怖値 | 危険判定 (>= 0.30) |
|---:|---:|:---:|
| 0 | 0.000 | no |
| 1 | 0.126 | no |
| 2 | 0.229 | no |
| 3 | 0.314 | yes |
| 4 | 0.384 | yes |
| 5 | 0.440 | yes |
| 6 | 0.487 | yes |

Demo C 救援の影響：洞窟の実効恐怖値 救援前 0.450 → 同じ場所で救援後 0.355

## Demo C  選択確率 [%]（文脈 = cave、場所の恐怖は各個体の空間記憶）
| 時点 | FOLLOW_CLOSE | FOLLOW_NORMAL | EXPLORE_NEARBY | APPROACH_PLAYER | DEFEND_PLAYER | RETREAT | AVOID_LOCATION | REST_NEAR_PLAYER |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 未経験の個体・洞窟 | 3.7 | 21.6 | 58.8 | 13.0 | 0.0 | 1.4 | 0.0 | 1.5 |
| 経験後・危険な洞窟 (2 分後) | 8.9 | 14.6 | 4.9 | 15.6 | 0.0 | 15.5 | 38.7 | 1.8 |
| 経験後・別の洞窟 (2 分後) | 13.2 | 23.6 | 11.8 | 23.5 | 0.0 | 25.1 | 0.0 | 2.7 |
| 経験後・危険な洞窟 (32 分後) | 6.6 | 19.6 | 11.9 | 18.4 | 0.0 | 9.8 | 30.9 | 2.9 |
| 経験後・別の洞窟 (32 分後) | 8.6 | 27.9 | 25.5 | 24.4 | 0.0 | 9.7 | 0.0 | 3.8 |
32 分後の洞窟の実効恐怖値 0.355

## Demo D  標準テストバッテリ（200 決定 × 5 セッション、平均 ± 標準偏差、割合 [%]）
| 個体 | APPROACH_PLAYER | FOLLOW_CLOSE | FOLLOW_NORMAL | EXPLORE_NEARBY | DEFEND_PLAYER | 平均飼い主距離 [blocks] |
|---|---:|---:|---:|---:|---:|---:|
| 未育成 | 14.3 ± 2.0 | 3.4 ± 1.2 | 19.1 ± 3.8 | 53.4 ± 2.7 | 7.1 ± 1.7 | 11.9 ± 1.4 |
| Wolf A（飼い主中心 10 分） | 63.7 ± 2.9 | 10.4 ± 2.1 | 6.8 ± 1.3 | 11.9 ± 2.2 | 6.8 ± 2.6 | 2.0 ± 0.3 |
| Wolf B（探索中心 10 分） | 0.0 ± 0.0 | 0.0 ± 0.0 | 0.4 ± 0.4 | 99.4 ± 0.5 | 0.2 ± 0.3 | 39.0 ± 0.2 |

Wolf A state: WolfInternalState{trust=1.0, attachment=1.0, fear=0.239, stress=0.0, curiosity=0.5, autonomy=0.5, energy=1.0}
Wolf A preference: PreferenceVector{follow_close=0.1729, follow_normal=0.0131, explore_nearby=-0.0523, approach_player=0.2355, defend_player=0.0, retreat=0.0, avoid_location=0.0, rest_near_player=0.0}
Wolf B state: WolfInternalState{trust=0.5, attachment=0.5, fear=0.006, stress=0.2, curiosity=0.948, autonomy=1.0, energy=0.96}
Wolf B preference: PreferenceVector{follow_close=-0.0466, follow_normal=0.0, explore_nearby=0.2426, approach_player=0.0, defend_player=0.0, retreat=0.0, avoid_location=0.0, rest_near_player=0.0}
