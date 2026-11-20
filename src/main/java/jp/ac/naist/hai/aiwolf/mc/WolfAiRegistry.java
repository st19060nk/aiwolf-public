package jp.ac.naist.hai.aiwolf.mc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.world.PersistentState;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.AnonymousIdMapper;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;

/**
 * 個体 UUID → ランタイムの対応表。
 *
 * <p>個体の同一性は<strong>エンティティ UUID のみ</strong>で決まる。名前・座標・
 * インデックスで識別してはならない。</p>
 *
 * <p>ここが実データの持ち主で、ディスクへの保存は
 * {@code WolfAiPersistentState} が担当する。ワールドを閉じるときにレジストリを空にするので、
 * 別のワールドを開いても前のワールドの個体が混ざることはない。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class WolfAiRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("AIWolfResearch");

    private static final Map<UUID, WolfAiController> CONTROLLERS = new HashMap<>();

    private static long experimentSeed = AiWolfParameters.DEFAULT_EXPERIMENT_SEED;
    private static ExperimentalCondition defaultCondition = AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION;
    private static PersistentState persistentState;
    private static AnonymousIdMapper anonymousIdMapper =
            AnonymousIdMapper.withSalt(new java.util.Random().nextLong());

    private WolfAiRegistry() {
        throw new AssertionError("static registry");
    }

    /**
     * 対象のオオカミのランタイムを取得し、なければ初期状態で作る。
     *
     * @param wolf 飼い慣らされたオオカミ
     * @return ランタイム
     */
    public static WolfAiController getOrCreate(WolfEntity wolf) {
        UUID uuid = wolf.getUuid();
        WolfAiController controller = CONTROLLERS.get(uuid);
        if (controller == null) {
            controller = new WolfAiController(
                    new WolfAiAgent(uuid.toString(), defaultCondition, experimentSeed));
            controller.agent().setLearningFrozen(DemoMode.isEvaluationActive());
            CONTROLLERS.put(uuid, controller);
            markDirty();
        }
        // テイム直後や譲渡後に飼い主が決まるため、毎回追従させる。
        String ownerId = WolfAiController.ownerResearchId(wolf);
        if (ownerId != null && !ownerId.equals(controller.agent().ownerResearchId())) {
            controller.agent().setOwnerResearchId(ownerId);
            markDirty();
        }
        return controller;
    }

    /**
     * 永続化から復元したランタイムを登録する。
     *
     * @param uuid       個体 UUID
     * @param controller 復元済みのランタイム
     */
    public static void restore(UUID uuid, WolfAiController controller) {
        controller.agent().setLearningFrozen(DemoMode.isEvaluationActive());
        CONTROLLERS.put(uuid, controller);
    }

    /**
     * デモ用：個体のランタイムを差し替える（スナップショットの復元・デモリセット）。
     *
     * <p>差し替え後の個体にも現在の評価モードを反映し、次回のワールド保存で書き出す。</p>
     *
     * @param wolf       対象のオオカミ
     * @param controller 新しいランタイム（個体 ID が一致していること）
     */
    public static void replaceForDemo(WolfEntity wolf, WolfAiController controller) {
        if (!wolf.getUuidAsString().equals(controller.agent().wolfId())) {
            throw new IllegalArgumentException("controller belongs to a different wolf");
        }
        controller.agent().setLearningFrozen(DemoMode.isEvaluationActive());
        CONTROLLERS.put(wolf.getUuid(), controller);
        // 飼い主の研究 ID を現在の飼い主へ揃える。
        getOrCreate(wolf);
        markDirty();
    }

    /**
     * 評価モードを全個体へ反映する。
     *
     * @param frozen 学習を凍結するなら true
     */
    public static void applyLearningFreeze(boolean frozen) {
        for (WolfAiController controller : CONTROLLERS.values()) {
            controller.agent().setLearningFrozen(frozen);
        }
    }

    /**
     * 個体の匿名 ID を返す（研究ログ用）。{@code wolf-} + ワールドの salt でハッシュ化した値。
     *
     * <p>エンティティ UUID は個人情報ではないが、セーブデータと突き合わせれば個体を特定できる。
     * 公開・共有する解析では、こちらの ID を使う。</p>
     *
     * @param wolfUuid 個体の UUID
     * @return 匿名 ID
     */
    public static String wolfSubjectId(UUID wolfUuid) {
        String subject = anonymousId(wolfUuid);
        return subject == null ? null : "wolf-" + subject.substring(AnonymousIdMapper.PREFIX.length());
    }

    /**
     * 個体の短い表示用ラベルを返す。生の UUID を画面に出さないために使う。
     *
     * <p>ワールドの salt でハッシュ化した値の先頭だけを使うので、UUID は復元できない。
     * 名札で名前が付いていれば、それを先頭に付ける（Demo D の Wolf A / B 用）。</p>
     *
     * @param wolf 対象のオオカミ
     * @return {@code "A (WOLF-1a2b3c)"} のようなラベル
     */
    public static String displayLabel(WolfEntity wolf) {
        String subject = anonymousId(wolf.getUuid());
        String hash = subject == null ? "------"
                : subject.substring(AnonymousIdMapper.PREFIX.length(),
                        Math.min(subject.length(), AnonymousIdMapper.PREFIX.length() + 6));
        String label = "WOLF-" + hash;
        return wolf.hasCustomName() ? wolf.getCustomName().getString() + " (" + label + ")" : label;
    }

    /**
     * 永続化状態が読み込まれたときに呼ばれる。
     *
     * @param state    永続化状態
     * @param restored 復元した個体数
     */
    public static void onStateLoaded(PersistentState state, int restored) {
        persistentState = state;
        LOGGER.info("Restored {} wolf AI record(s) from the world save", restored);
    }

    /**
     * 永続化状態を結び付ける。新規ワールドでレコードが 1 件もない場合にも呼ばれる。
     *
     * @param state 永続化状態
     */
    public static void bind(PersistentState state) {
        persistentState = state;
    }

    /**
     * 次回のワールド保存で書き出されるよう印を付ける。
     */
    public static void markDirty() {
        if (persistentState != null) {
            persistentState.markDirty();
        }
    }

    /**
     * 対象のオオカミのランタイムを取得する（作成はしない）。
     *
     * @param wolf 対象のオオカミ
     * @return ランタイム。未登録なら {@link Optional#empty()}
     */
    public static Optional<WolfAiController> find(WolfEntity wolf) {
        return Optional.ofNullable(CONTROLLERS.get(wolf.getUuid()));
    }

    /**
     * その相手との戦闘を追跡しているランタイムすべてに処理を適用する。
     *
     * <p>敵の死亡や飼い主の介入を伝える相手を、<strong>距離ではなく
     * 戦闘の対応関係で</strong>決めるための入口。退避中の個体にも確実に届く。</p>
     *
     * <p>研究環境で同時に登録される個体数は小さく、判定は UUID の照会 1 回なので
     * 全走査で足りる。毎 tick ではなく、敵の死亡と飼い主の攻撃のときだけ呼ばれる。</p>
     *
     * @param hostileUuid 相手の UUID
     * @param action      適用する処理
     */
    public static void forEachTrackingCombat(UUID hostileUuid, Consumer<WolfAiController> action) {
        if (hostileUuid == null) {
            return;
        }
        for (WolfAiController controller : CONTROLLERS.values()) {
            if (controller.observer().combatTracker().tracks(hostileUuid)) {
                action.accept(controller);
            }
        }
    }

    /**
     * 登録済みランタイムの不変ビューを返す。
     *
     * @return UUID → ランタイム
     */
    public static Map<UUID, WolfAiController> all() {
        return Collections.unmodifiableMap(CONTROLLERS);
    }

    /**
     * 対象を初期状態へ戻す（{@code /wolfai reset}）。
     *
     * @param wolf 対象のオオカミ
     */
    public static void reset(WolfEntity wolf) {
        CONTROLLERS.remove(wolf.getUuid());
        getOrCreate(wolf);
        markDirty();
    }

    /**
     * プレイヤー UUID を研究用の匿名 ID へ変換する。
     *
     * <p>研究データにプレイヤー UUID をそのまま残さないための唯一の入口。</p>
     *
     * @param playerUuid プレイヤー UUID（{@code null} 可）
     * @return 匿名 ID
     */
    public static String anonymousId(UUID playerUuid) {
        return anonymousIdMapper.toSubjectId(playerUuid);
    }

    /** @return 匿名化に使っている salt（永続化用） */
    public static long anonymitySalt() {
        return anonymousIdMapper.salt();
    }

    /**
     * 匿名化の salt を設定する。ワールドごとに固定し、変えてはならない
     * （変えると同じ参加者が別 ID になり、履歴の連続性が切れる）。
     *
     * @param salt salt
     */
    public static void setAnonymitySalt(long salt) {
        anonymousIdMapper = AnonymousIdMapper.withSalt(salt);
    }

    /** @return 実験シード */
    public static long experimentSeed() {
        return experimentSeed;
    }

    /**
     * 実験シードを設定する。既存個体のシードは変わらない。
     *
     * @param seed 実験シード
     */
    public static void setExperimentSeed(long seed) {
        experimentSeed = seed;
    }

    /** @return 新規個体へ割り当てる既定の実験条件 */
    public static ExperimentalCondition defaultCondition() {
        return defaultCondition;
    }

    /**
     * 新規個体へ割り当てる既定の実験条件を設定する。
     *
     * @param condition 実験条件
     */
    public static void setDefaultCondition(ExperimentalCondition condition) {
        defaultCondition = condition;
        markDirty();
    }

    /**
     * ワールドを閉じるときにメモリ上の内容を破棄する。
     *
     * <p>保存はこれより前にワールドのセーブ処理が行っている。別のワールドを開いたときに
     * 前のワールドの個体が残らないようにするための後始末である。</p>
     */
    public static void clear() {
        CONTROLLERS.clear();
        persistentState = null;
        experimentSeed = AiWolfParameters.DEFAULT_EXPERIMENT_SEED;
        defaultCondition = AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION;
        anonymousIdMapper = AnonymousIdMapper.withSalt(new java.util.Random().nextLong());
    }
}
