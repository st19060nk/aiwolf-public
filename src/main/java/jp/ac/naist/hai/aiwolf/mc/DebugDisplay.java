package jp.ac.naist.hai.aiwolf.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.demo.DebugOverlay;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.demo.WolfDebugView;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * デバッグ表示。実験者だけが使う。
 *
 * <p><strong>通常のプレイ中は内部数値を絶対に表示しない。</strong>参加者が
 * 「性格値を見たから個性を判断した」という交絡を防ぐためであり、本研究の妥当性に直結する。
 * そのため既定は無効で、operator が明示的に有効化したプレイヤーにのみ表示する。</p>
 *
 * <p>表示は画面右端のサイドバー（{@link DebugOverlay}）へ、既定 10 ticks ごとに更新する。
 * {@code /wolfai debug basic|detailed|off} で切り替える。</p>
 */
public final class DebugDisplay {

    /** 評価モードの通知を出す権限レベル（/wolfai と同じ）。 */
    private static final int OPERATOR_LEVEL = 2;

    /** 表示対象のオオカミを探す半径 [blocks]。 */
    private static final double SEARCH_RADIUS = 24.0;

    private DebugDisplay() {
        throw new AssertionError("utility class");
    }

    /**
     * 指定プレイヤーでデバッグ表示が有効かを返す。
     *
     * @param playerUuid プレイヤー UUID
     * @return 有効なら true
     */
    public static boolean isEnabled(UUID playerUuid) {
        return DebugOverlay.level(playerUuid) != WolfDebugView.Level.OFF;
    }

    /** すべてのデバッグ表示を解除する。 */
    public static void clear() {
        DebugOverlay.clear();
    }

    /**
     * サーバ tick ごとに呼ばれ、必要なときだけ表示を更新する。
     *
     * <p>表示は {@link DebugOverlay}（画面右端のサイドバー）が担当する。
     * 評価モード中は、オーバーレイを出していない operator にもアクションバーで知らせる。</p>
     *
     * @param server サーバ
     */
    public static void tick(MinecraftServer server) {
        boolean evaluation = DemoMode.isEvaluationActive();
        if (!DebugOverlay.anyActive() && !evaluation) {
            return;
        }
        if (server.getTicks() % AiWolfParameters.DEBUG_DISPLAY_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (isEnabled(player.getUuid())) {
                DebugOverlay.render(player);
            } else if (evaluation && player.hasPermissionLevel(OPERATOR_LEVEL)) {
                player.sendMessageToClient(Text.literal("[EVALUATION MODE] 学習を凍結中")
                        .formatted(Formatting.LIGHT_PURPLE), true);
            }
        }
    }

    /**
     * オオカミ 1 体の現在状態を 1 行にまとめる。
     *
     * @param wolf 対象のオオカミ
     * @return 表示用テキスト
     */
    public static Text describe(WolfEntity wolf) {
        WolfAiController controller = WolfAiRegistry.getOrCreate(wolf);
        WolfInternalState state = controller.agent().state();
        BehaviorType behavior = controller.currentBehavior();

        String summary = String.format(Locale.ROOT,
                "[%s] %s  trust %.2f  att %.2f  fear %.2f  cur %.2f  auto %.2f  str %.2f  en %.2f",
                controller.agent().condition().id(),
                behavior == null ? "-" : behavior.id(),
                state.trust(), state.attachment(), state.fear(), state.curiosity(),
                state.autonomy(), state.stress(), state.energy());
        return Text.literal(summary).formatted(Formatting.AQUA);
    }

    /**
     * 近傍のオオカミの内訳。対象が見つからなかったときに、
     * 「そもそもいない」「他人の犬」「野生のまま」を切り分けるために使う。
     *
     * @param total       近傍のオオカミの総数
     * @param tamed       そのうち飼い慣らされている数
     * @param owned       そのうち自分が飼い主である数
     * @param nearestOwned 自分の飼い犬で最も近い個体（いなければ {@code null}）
     * @param searchRadius 探索半径 [blocks]
     * @param details      個体ごとの内訳（所有者 UUID の突き合わせ用）
     */
    public record NearbyWolfSurvey(int total, int tamed, int owned,
                                   WolfEntity nearestOwned, double searchRadius,
                                   List<String> details) {

        /**
         * 見つからなかった理由を人間可読の文にする。
         *
         * @return 説明文
         */
        public String describeMiss() {
            if (total == 0) {
                return String.format(Locale.ROOT,
                        "半径 %.0f ブロック内にオオカミが 1 匹もいません", searchRadius);
            }
            if (tamed == 0) {
                return String.format(Locale.ROOT,
                        "オオカミは %d 匹いますが、まだ飼い慣らされていません（骨を与えてください）", total);
            }
            return String.format(Locale.ROOT,
                    "飼い慣らされたオオカミが %d 匹いますが、あなたが飼い主の個体はいません", tamed);
        }
    }

    /**
     * 近傍のオオカミを調べる。
     *
     * @param player 対象プレイヤー
     * @return 内訳
     */
    public static NearbyWolfSurvey surveyNearbyWolves(ServerPlayerEntity player) {
        List<WolfEntity> wolves = player.getWorld().getEntitiesByClass(WolfEntity.class,
                player.getBoundingBox().expand(SEARCH_RADIUS), wolf -> true);

        int tamed = 0;
        int owned = 0;
        WolfEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (WolfEntity wolf : wolves) {
            if (!wolf.isTamed()) {
                continue;
            }
            tamed++;
            if (!player.getUuid().equals(wolf.getOwnerUuid())) {
                continue;
            }
            owned++;
            double distance = player.squaredDistanceTo(wolf);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = wolf;
            }
        }
        List<String> details = new ArrayList<>();
        for (WolfEntity wolf : wolves) {
            details.add(String.format(Locale.ROOT,
                    "wolf=%s dist=%.1f tamed=%s ownerUuid=%s",
                    wolf.getUuidAsString().substring(0, 8),
                    Math.sqrt(player.squaredDistanceTo(wolf)),
                    wolf.isTamed(),
                    wolf.getOwnerUuid() == null ? "none" : wolf.getOwnerUuid().toString()));
        }
        return new NearbyWolfSurvey(wolves.size(), tamed, owned, nearest, SEARCH_RADIUS, details);
    }

    /**
     * プレイヤーが飼っている最も近いオオカミを探す。
     *
     * @param player 対象プレイヤー
     * @return 最近接のオオカミ。いなければ {@code null}
     */
    public static WolfEntity findNearestOwnWolf(ServerPlayerEntity player) {
        return surveyNearbyWolves(player).nearestOwned();
    }
}
