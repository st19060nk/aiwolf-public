package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.mc.ContextBuilder;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * Wolf Debug Overlay。画面右端のサイドバーに、対象個体の「いま何をなぜしているか」を出す。
 *
 * <p><strong>パケットを対象プレイヤーへ直接送るだけで、サーバの scoreboard には何も登録しない。</strong>
 * そのためワールドのセーブデータは汚れず、他のプレイヤー（実験参加者）にも見えない。
 * クライアント側の MOD も不要である。</p>
 *
 * <p>表示は個体を一切変更しない。{@link WolfAiRegistry#find} で既存のランタイムを読むだけで、
 * 未登録の個体を新規作成することもない。中身は {@link WolfDebugView} が組み立てる。</p>
 *
 * <p>注意：プレイヤー側で別のサイドバー表示を使っていた場合、表示を消したあとは
 * その表示も消えたままになる（{@code /scoreboard objectives setdisplay} で戻せる）。</p>
 */
public final class DebugOverlay {

    private static final String OBJECTIVE_NAME = "aiwolf_debug";

    /** 見ていた個体から目を離しても表示を保つ時間 [ticks]。撮影中のちらつき防止。 */
    private static final long LOOK_GRACE_TICKS = 60L;

    private static final Map<UUID, PlayerView> VIEWS = new HashMap<>();

    /** プレイヤーごとの表示状態。 */
    private static final class PlayerView {
        WolfDebugView.Level level = WolfDebugView.Level.OFF;
        boolean objectiveShown;
        String title;
        List<String> sentLines = new ArrayList<>();
        UUID stickyWolf;
        long stickyUntilTick;
        String lastRegionKey;
        UUID lastRegionWolf;
    }

    private DebugOverlay() {
        throw new AssertionError("utility class");
    }

    /**
     * 表示の詳しさを設定する。OFF にするとサイドバーを消す。
     *
     * @param player プレイヤー
     * @param level  詳しさ
     */
    public static void setLevel(ServerPlayerEntity player, WolfDebugView.Level level) {
        PlayerView view = VIEWS.computeIfAbsent(player.getUuid(), key -> new PlayerView());
        view.level = level;
        if (level == WolfDebugView.Level.OFF) {
            hide(player, view);
            VIEWS.remove(player.getUuid());
        }
    }

    /**
     * @param playerUuid プレイヤー UUID
     * @return 現在の詳しさ
     */
    public static WolfDebugView.Level level(UUID playerUuid) {
        PlayerView view = VIEWS.get(playerUuid);
        return view == null ? WolfDebugView.Level.OFF : view.level;
    }

    /** @return 表示中のプレイヤーがいれば true */
    public static boolean anyActive() {
        return !VIEWS.isEmpty();
    }

    /**
     * プレイヤーが切断したときに呼ぶ。再接続したクライアントは目的を知らないので、状態を捨てる。
     *
     * @param playerUuid プレイヤー UUID
     */
    public static void forget(UUID playerUuid) {
        VIEWS.remove(playerUuid);
    }

    /** すべての表示状態を捨てる（サーバ停止時）。 */
    public static void clear() {
        VIEWS.clear();
    }

    /**
     * 1 人分の表示を更新する。{@code DebugDisplay.tick} から一定間隔で呼ばれる。
     *
     * @param player プレイヤー
     */
    public static void render(ServerPlayerEntity player) {
        PlayerView view = VIEWS.get(player.getUuid());
        if (view == null || view.level == WolfDebugView.Level.OFF) {
            return;
        }
        long now = player.getServer() == null ? 0L : player.getServer().getTicks();
        WolfEntity wolf = resolveTarget(player, view, now);
        WolfAiController controller = wolf == null ? null : WolfAiRegistry.find(wolf).orElse(null);
        if (controller == null) {
            hide(player, view);
            return;
        }

        SpatialRegion region = ContextBuilder.currentRegion(wolf);
        List<String> lines = WolfDebugView.lines(view.level, controller.agent(), controller.lastDecision(),
                region, DemoMode.isEnabled(), DemoMode.isEvaluationActive());
        String title = "AI WOLF  " + WolfAiRegistry.displayLabel(wolf);
        show(player, view, title, lines);
        notifySpatialMemory(player, view, wolf, controller, region);
    }

    private static WolfEntity resolveTarget(ServerPlayerEntity player, PlayerView view, long now) {
        WolfEntity selected = DemoTargets.selected(player);
        if (selected != null) {
            return selected;
        }
        WolfEntity looked = DemoTargets.lookedAt(player);
        if (looked != null) {
            view.stickyWolf = looked.getUuid();
            view.stickyUntilTick = now + LOOK_GRACE_TICKS;
            return looked;
        }
        if (view.stickyWolf != null && now <= view.stickyUntilTick
                && player.getServerWorld().getEntity(view.stickyWolf) instanceof WolfEntity sticky
                && sticky.isAlive()) {
            return sticky;
        }
        view.stickyWolf = null;
        return null;
    }

    /**
     * 危険と記憶している領域へ入った瞬間だけ、アクションバーへ 1 行出す。
     * 画面中央を塞がないよう、チャットやタイトルは使わない。
     */
    private static void notifySpatialMemory(ServerPlayerEntity player, PlayerView view, WolfEntity wolf,
                                            WolfAiController controller, SpatialRegion region) {
        String key = region.key();
        boolean entered = !wolf.getUuid().equals(view.lastRegionWolf) || !key.equals(view.lastRegionKey);
        view.lastRegionWolf = wolf.getUuid();
        view.lastRegionKey = key;
        if (!entered) {
            return;
        }
        double fear = controller.agent().regionFear(region);
        if (fear >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD) {
            player.sendMessageToClient(Text.literal(String.format(Locale.ROOT,
                    "Spatial memory activated  Fear = %.2f", fear)).formatted(Formatting.GOLD), true);
        }
    }

    private static void show(ServerPlayerEntity player, PlayerView view, String title, List<String> lines) {
        if (!view.objectiveShown) {
            send(player, new ScoreboardObjectiveUpdateS2CPacket(objective(title),
                    ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
            send(player, new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective(title)));
            view.objectiveShown = true;
            view.title = title;
            view.sentLines = new ArrayList<>();
        } else if (!title.equals(view.title)) {
            send(player, new ScoreboardObjectiveUpdateS2CPacket(objective(title),
                    ScoreboardObjectiveUpdateS2CPacket.UPDATE_MODE));
            view.title = title;
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i < view.sentLines.size() && line.equals(view.sentLines.get(i))) {
                continue;
            }
            // スコアの降順で並ぶので、上の行ほど大きい値を付ける。数値自体は BlankNumberFormat で隠す。
            send(player, new ScoreboardScoreUpdateS2CPacket(holder(i), OBJECTIVE_NAME,
                    WolfDebugView.MAX_LINES - i, Optional.of(Text.literal(line)), Optional.empty()));
        }
        for (int i = lines.size(); i < view.sentLines.size(); i++) {
            send(player, new ScoreboardScoreResetS2CPacket(holder(i), OBJECTIVE_NAME));
        }
        view.sentLines = new ArrayList<>(lines);
    }

    private static void hide(ServerPlayerEntity player, PlayerView view) {
        if (!view.objectiveShown) {
            return;
        }
        send(player, new ScoreboardObjectiveUpdateS2CPacket(objective(view.title == null ? "" : view.title),
                ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
        view.objectiveShown = false;
        view.sentLines = new ArrayList<>();
        view.title = null;
    }

    private static ScoreboardObjective objective(String title) {
        // サーバの scoreboard には登録しない、パケット用の使い捨てインスタンス。
        return new ScoreboardObjective(new Scoreboard(), OBJECTIVE_NAME, ScoreboardCriterion.DUMMY,
                Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD),
                ScoreboardCriterion.RenderType.INTEGER, false, BlankNumberFormat.INSTANCE);
    }

    private static String holder(int index) {
        return String.format(Locale.ROOT, "aiwolf_line_%02d", index);
    }

    private static void send(ServerPlayerEntity player, net.minecraft.network.packet.Packet<?> packet) {
        player.networkHandler.sendPacket(packet);
    }
}
