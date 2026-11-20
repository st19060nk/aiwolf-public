package jp.ac.naist.hai.aiwolf.runtime;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 決定と決定のあいだに受けたダメージを、<strong>加害者ごとに分けて</strong>まとめる入れ物。
 * Minecraft に依存しない。
 *
 * <h2>なぜ加害者ごとに分けるのか</h2>
 *
 * <p>ひとつの決定区間に Zombie から 2、Skeleton から 3 のダメージを受けたとき、
 * これを「Skeleton から 5」と合算してはならない。戦闘記録は相手ごとに開いており、
 * 合算した量をどちらか一方の戦闘へ入れると、実際には軽傷だった相手が
 * 重い戦闘として記録され、もう一方の被害は消える。どの相手との経験が
 * どれだけ痛かったかが入れ替わるため、場所や相手に対する学習が誤った方向へ育つ。</p>
 *
 * <h2>なぜそれでも研究イベントは 1 件なのか</h2>
 *
 * <p>ダメージの事実は 1 発ずつ正確に扱う必要がある一方、研究イベントとしての
 * 「傷ついた」を 1 発ごとに発火させると、1 回の戦闘で 3 回噛まれた個体が
 * 3 倍の速さで学習することになり、パイロットを取ったときと学習速度が変わる。
 * そこで帰属の正確さ（加害者ごと）と発火頻度（決定ごとに 1 件）を分ける。</p>
 *
 * <h2>どの加害者を代表として選ぶか</h2>
 *
 * <p>複数の相手から受けた場合、研究イベントの帰属先は次の順で決める。
 * <strong>この順序は完全に決定論的で、マップの走査順に依存しない。</strong>
 * 依存させると、同じ状況でも実行のたびに帰属先が変わり再現性が失われる。</p>
 *
 * <ol>
 *   <li>その区間に与えた合計ダメージが大きい相手（実際にいちばん痛かった経験を代表とする）</li>
 *   <li>同点なら、最後に攻撃してきた時刻が遅い相手（直近の脅威を優先する）</li>
 *   <li>それも同点なら、識別子の辞書順で小さい相手（順序を一意に決めるためだけの規則）</li>
 * </ol>
 *
 * <p>一方、傷の重さ（瀕死か低 HP か）の判定には<strong>区間内で最も低かった HP 比率</strong>を
 * 使う。誰に殴られたかとは無関係に、その区間でどこまで追い込まれたかが重さだからである。</p>
 *
 * <p>環境ダメージ（落下・溶岩・炎など）は加害者のいないダメージとして
 * {@code null} の識別子で扱う。合計には含めるが、戦闘記録は作らない。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class DamageAccumulator {

    /** 加害者のいないダメージを表す内部キー。外部へは {@code null} として見せる。 */
    private static final String ENVIRONMENT = "";

    /**
     * 代表となる加害者を選ぶ順序。合計ダメージ降順 → 最終被弾時刻降順 → 識別子昇順。
     * 走査順に依存させないため、最後の規則で必ず一意になる。
     */
    private static final Comparator<Source> SELECTION = Comparator
            .comparingDouble(Source::total).reversed()
            .thenComparing(Comparator.comparingLong(Source::lastHitTick).reversed())
            .thenComparing(Source::key);

    private final Map<String, Source> sources = new LinkedHashMap<>();

    private double lowestHealthRatio = 1.0;
    private double totalDamage;
    private int totalHits;

    /**
     * 1 発ぶんのダメージを加える。
     *
     * @param sourceKey        加害者の識別子。環境ダメージなら {@code null}
     * @param amount           実際に受けたダメージ量（軽減後）。0 以下は無視する
     * @param healthRatioAfter 被弾直後の HP 比率
     * @param tick             発生ワールド時刻
     */
    public void add(String sourceKey, double amount, double healthRatioAfter, long tick) {
        if (amount <= 0.0) {
            return;
        }
        String key = sourceKey == null ? ENVIRONMENT : sourceKey;
        sources.computeIfAbsent(key, Source::new).add(amount, tick);
        totalDamage += amount;
        totalHits++;
        lowestHealthRatio = Math.min(lowestHealthRatio, healthRatioAfter);
    }

    /**
     * 溜まったダメージを取り出して空にする。
     *
     * <p>合計が {@code MIN_SIGNIFICANT_DAMAGE} に満たない場合は空を返すが、
     * その場合も内容は捨てる。次の決定まで持ち越すと、
     * いつの被弾か分からないダメージが後から効いてしまう。</p>
     *
     * <p>閾値は<strong>区間の合計</strong>に対して見る。刻むように攻撃してくる敵の被害が
     * 丸ごと消えないようにするためで、加害者ごとに閾値を課すと取りこぼす。</p>
     *
     * @return 溜まっていたダメージ。意味のある量が無ければ {@link Optional#empty()}
     */
    public Optional<Drained> drain() {
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        Source selected = sources.values().stream().min(SELECTION).orElseThrow();
        Drained drained = new Drained(
                ENVIRONMENT.equals(selected.key()) ? null : selected.key(),
                selected.total(), selected.hits(), selected.lastHitTick(),
                totalDamage, totalHits, sources.size(), lowestHealthRatio);
        boolean significant = totalDamage >= AiWolfParameters.MIN_SIGNIFICANT_DAMAGE;
        clear();
        return significant ? Optional.of(drained) : Optional.empty();
    }

    /** 溜まっている内容を捨てる。 */
    public void clear() {
        sources.clear();
        totalDamage = 0.0;
        totalHits = 0;
        lowestHealthRatio = 1.0;
    }

    /** @return 現在溜まっている合計ダメージ */
    public double pendingTotal() {
        return totalDamage;
    }

    /** @return 現在溜まっている被弾回数 */
    public int pendingHits() {
        return totalHits;
    }

    /** @return 現在溜まっている加害者の数 */
    public int pendingSources() {
        return sources.size();
    }

    /**
     * その加害者から現在溜まっているダメージ量を返す。
     *
     * @param sourceKey 加害者の識別子（環境ダメージなら {@code null}）
     * @return 合計ダメージ量。記録が無ければ 0
     */
    public double pendingFrom(String sourceKey) {
        Source source = sources.get(sourceKey == null ? ENVIRONMENT : sourceKey);
        return source == null ? 0.0 : source.total();
    }

    /**
     * 取り出したダメージ。
     *
     * @param hostileId          代表として選ばれた加害者の識別子。環境ダメージなら {@code null}
     * @param selectedTotal      その加害者から受けた合計ダメージ量
     * @param selectedHits       その加害者から受けた被弾回数
     * @param lastHitTick        その加害者から最後に被弾したワールド時刻
     * @param totalDamage        区間内の合計ダメージ量（全加害者）
     * @param totalHits          区間内の被弾回数（全加害者）
     * @param sourceCount        区間内に関与した加害者の数
     * @param lowestHealthRatio  区間内で最も低かった HP 比率
     */
    public record Drained(String hostileId, double selectedTotal, int selectedHits,
                          long lastHitTick, double totalDamage, int totalHits,
                          int sourceCount, double lowestHealthRatio) {

        /** @return 加害者のいない環境ダメージが代表なら true */
        public boolean environmental() {
            return hostileId == null;
        }
    }

    /** 1 人の加害者ぶんの集計。 */
    private static final class Source {

        private final String key;
        private double total;
        private long lastHitTick = Long.MIN_VALUE;
        private int hits;

        private Source(String key) {
            this.key = key;
        }

        private void add(double amount, long tick) {
            total += amount;
            lastHitTick = Math.max(lastHitTick, tick);
            hits++;
        }

        private String key() {
            return key;
        }

        private double total() {
            return total;
        }

        private long lastHitTick() {
            return lastHitTick;
        }

        private int hits() {
            return hits;
        }
    }
}
