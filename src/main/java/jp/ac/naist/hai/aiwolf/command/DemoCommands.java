package jp.ac.naist.hai.aiwolf.command;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import jp.ac.naist.hai.aiwolf.demo.BehaviorHistory;
import jp.ac.naist.hai.aiwolf.demo.BehaviorSummaryReport;
import jp.ac.naist.hai.aiwolf.demo.DebugOverlay;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.demo.DemoReset;
import jp.ac.naist.hai.aiwolf.demo.DemoScenarioGuide;
import jp.ac.naist.hai.aiwolf.demo.DemoSnapshotStore;
import jp.ac.naist.hai.aiwolf.demo.DemoTargets;
import jp.ac.naist.hai.aiwolf.demo.SpatialMemoryReport;
import jp.ac.naist.hai.aiwolf.demo.WolfInspectReport;
import jp.ac.naist.hai.aiwolf.demo.WolfDebugView;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.mc.ContextBuilder;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;

/**
 * Research Demo Mode のコマンド群
 * （{@code /wolfai demo|debug|snapshot|evaluation|select|inspect|memory spatial|summary}）。
 *
 * <p><strong>行動を直接決めるコマンド、選好や状態の値を書き換えるコマンドは置かない。</strong>
 * ここにあるのは表示・研究初期値へのリセット・保存した状態の復元・学習の一時停止だけである。
 * 個体差は必ず実際の経験（研究イベント処理）を通して作る。</p>
 *
 * <p>状態を変えるコマンド（reset / snapshot / evaluation）は Demo Mode 中しか使えない。
 * 本実験の最中に誤って個体を巻き戻すことを防ぐためである。</p>
 */
public final class DemoCommands {

    private static DemoSnapshotStore snapshots = new DemoSnapshotStore(null);

    private DemoCommands() {
        throw new AssertionError("utility class");
    }

    /**
     * サーバ開始時にスナップショットの保存先を決める。
     *
     * @param server サーバ（未使用。将来の拡張用）
     */
    public static void start(MinecraftServer server) {
        Path root = ResearchLogger.directory();
        snapshots = new DemoSnapshotStore(root == null ? null
                : root.resolve(DemoMode.LOG_SUBDIRECTORY).resolve("snapshots"));
    }

    /** サーバ停止時の後始末。Demo Mode と評価モードは必ず off に戻る。 */
    public static void stop() {
        DemoMode.reset();
        DemoTargets.clear();
        DebugOverlay.clear();
        BehaviorHistory.SHARED.clear();
        snapshots = new DemoSnapshotStore(null);
    }

    /**
     * {@code /wolfai} の下へデモ用サブコマンドを足す。
     *
     * @param root {@code /wolfai} のビルダ
     * @return 同じビルダ
     */
    static LiteralArgumentBuilder<ServerCommandSource> attach(LiteralArgumentBuilder<ServerCommandSource> root) {
        return root
                .then(CommandManager.literal("demo")
                        .then(CommandManager.literal("on").executes(c -> demo(c.getSource(), true)))
                        .then(CommandManager.literal("off").executes(c -> demo(c.getSource(), false)))
                        .then(CommandManager.literal("status").executes(c -> demoStatus(c.getSource())))
                        .then(CommandManager.literal("reset").executes(c -> demoReset(c.getSource())))
                        .then(scenarioCommands()))
                .then(CommandManager.literal("debug")
                        .then(CommandManager.literal("on")
                                .executes(c -> debug(c.getSource(), WolfDebugView.Level.BASIC)))
                        .then(CommandManager.literal("basic")
                                .executes(c -> debug(c.getSource(), WolfDebugView.Level.BASIC)))
                        .then(CommandManager.literal("detailed")
                                .executes(c -> debug(c.getSource(), WolfDebugView.Level.DETAILED)))
                        .then(CommandManager.literal("off")
                                .executes(c -> debug(c.getSource(), WolfDebugView.Level.OFF))))
                .then(CommandManager.literal("snapshot")
                        .then(CommandManager.literal("save")
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(c -> snapshotSave(c.getSource(),
                                                StringArgumentType.getString(c, "name")))))
                        .then(CommandManager.literal("restore")
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(c -> snapshotRestore(c.getSource(),
                                                StringArgumentType.getString(c, "name")))))
                        .then(CommandManager.literal("list").executes(c -> snapshotList(c.getSource()))))
                .then(CommandManager.literal("evaluation")
                        .then(CommandManager.literal("start").executes(c -> evaluation(c.getSource(), true)))
                        .then(CommandManager.literal("stop").executes(c -> evaluation(c.getSource(), false))))
                .then(CommandManager.literal("inspect").executes(c -> inspect(c.getSource())))
                .then(CommandManager.literal("summary")
                        .executes(c -> summary(c.getSource()))
                        .then(CommandManager.literal("reset").executes(c -> summaryReset(c.getSource()))))
                .then(CommandManager.literal("memory")
                        .then(CommandManager.literal("spatial").executes(c -> memorySpatial(c.getSource()))))
                .then(CommandManager.literal("select")
                        .executes(c -> select(c.getSource()))
                        .then(CommandManager.literal("clear").executes(c -> selectClear(c.getSource()))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> scenarioCommands() {
        LiteralArgumentBuilder<ServerCommandSource> scenario = CommandManager.literal("scenario");
        for (DemoScenarioGuide.Scenario value : DemoScenarioGuide.Scenario.values()) {
            scenario.then(CommandManager.literal(value.id()).executes(c -> scenario(c.getSource(), value)));
        }
        return scenario;
    }

    // ------------------------------------------------------------------
    // demo
    // ------------------------------------------------------------------

    private static int demo(ServerCommandSource source, boolean enabled) {
        boolean wasEvaluating = DemoMode.isEvaluationActive();
        // 切り替えの前に溜まっている行を書き出す。振り分けは記録時に決まっているので
        // 混ざることはないが、境界をファイル上でも分かりやすくする。
        ResearchLogger.flush();
        DemoMode.setEnabled(enabled);
        if (!enabled && wasEvaluating) {
            WolfAiRegistry.applyLearningFreeze(false);
        }
        if (enabled) {
            info(source, "Research Demo Mode: ON", Formatting.LIGHT_PURPLE);
            gray(source, "研究ログは aiwolf-research/demo/ へ demoMode=true 付きで書き出されます（本実験データとは別）");
            gray(source, "行動計算・学習則・研究パラメータは変わりません。サーバ再起動で自動的に OFF になります");
        } else {
            info(source, "Research Demo Mode: OFF（研究ログは通常の出力先へ戻りました）", Formatting.YELLOW);
            if (wasEvaluating) {
                gray(source, "評価モードも終了し、学習を再開しました");
            }
        }
        return 1;
    }

    private static int demoStatus(ServerCommandSource source) {
        info(source, "Demo Mode: " + (DemoMode.isEnabled() ? "ON" : "OFF")
                + "   Evaluation: " + (DemoMode.isEvaluationActive() ? "ON（学習凍結中）" : "OFF"),
                Formatting.GOLD);
        return 1;
    }

    private static int demoReset(ServerCommandSource source) throws CommandSyntaxException {
        if (!requireDemo(source)) {
            return 0;
        }
        WolfEntity wolf = target(source);
        if (wolf == null) {
            return 0;
        }
        WolfAiController previous = WolfAiRegistry.getOrCreate(wolf);
        WolfAiRegistry.replaceForDemo(wolf, DemoReset.freshController(previous));
        info(source, WolfAiRegistry.displayLabel(wolf) + " を研究初期状態へ戻しました", Formatting.YELLOW);
        gray(source, "状態・選好は研究の初期値、記憶・空間記憶・現在行動・タイマーは空です（条件 "
                + previous.agent().condition().id() + " は維持）");
        return 1;
    }

    // ------------------------------------------------------------------
    // debug
    // ------------------------------------------------------------------

    private static int debug(ServerCommandSource source, WolfDebugView.Level level) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DebugOverlay.setLevel(player, level);
        if (level == WolfDebugView.Level.OFF) {
            info(source, "デバッグ表示を無効にしました", Formatting.YELLOW);
            return 1;
        }
        info(source, "デバッグ表示: " + level.name().toLowerCase(java.util.Locale.ROOT)
                + "（画面右のサイドバー）", Formatting.YELLOW);
        gray(source, "対象: /wolfai select で選んだ個体、なければ見ている個体");
        source.sendFeedback(() -> Text.literal(
                "注意: 実験参加者には内部数値を見せないでください（個性判断の交絡になります）")
                .formatted(Formatting.RED), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // snapshot
    // ------------------------------------------------------------------

    private static int snapshotSave(ServerCommandSource source, String name) throws CommandSyntaxException {
        if (!requireDemoOrSession(source) || !requireValidName(source, name)) {
            return 0;
        }
        WolfEntity wolf = target(source);
        if (wolf == null) {
            return 0;
        }
        snapshots.save(WolfAiRegistry.getOrCreate(wolf), name);
        StudyCommands.logControl(source, "SNAPSHOT_SAVED", wolf, java.util.Map.of("snapshot", name));
        info(source, "[Demo] Snapshot '" + name + "' を保存しました: " + WolfAiRegistry.displayLabel(wolf),
                Formatting.GREEN);
        return 1;
    }

    private static int snapshotRestore(ServerCommandSource source, String name) throws CommandSyntaxException {
        if (!requireDemoOrSession(source) || !requireValidName(source, name)) {
            return 0;
        }
        WolfEntity wolf = target(source);
        if (wolf == null) {
            return 0;
        }
        var restored = snapshots.restore(wolf.getUuidAsString(), name);
        if (restored.isEmpty()) {
            info(source, "この個体の Snapshot '" + name + "' はありません（/wolfai snapshot list）", Formatting.RED);
            return 0;
        }
        WolfAiRegistry.replaceForDemo(wolf, restored.get());
        StudyCommands.logControl(source, "SNAPSHOT_RESTORED", wolf, java.util.Map.of("snapshot", name,
                "experimentalCondition", restored.get().agent().condition().id()));
        info(source, "[Demo] Snapshot '" + name + "' を復元しました: " + WolfAiRegistry.displayLabel(wolf),
                Formatting.GREEN);
        return 1;
    }

    private static int snapshotList(ServerCommandSource source) throws CommandSyntaxException {
        WolfEntity wolf = target(source);
        if (wolf == null) {
            return 0;
        }
        Map<String, Long> names = snapshots.list(wolf.getUuidAsString());
        info(source, "[Demo] Snapshots of " + WolfAiRegistry.displayLabel(wolf) + ": "
                + (names.isEmpty() ? "(なし)" : String.join(", ", names.keySet())), Formatting.GOLD);
        return names.size();
    }

    // ------------------------------------------------------------------
    // evaluation
    // ------------------------------------------------------------------

    private static int evaluation(ServerCommandSource source, boolean start) {
        if (start && !requireDemoOrSession(source)) {
            return 0;
        }
        boolean active = DemoMode.setEvaluationActive(start);
        WolfAiRegistry.applyLearningFreeze(active);
        StudyCommands.logControl(source, active ? "EVALUATION_STARTED" : "EVALUATION_STOPPED", null,
                java.util.Map.of("learningFrozen", active));
        if (active) {
            info(source, "[Demo] Evaluation Mode: ON", Formatting.LIGHT_PURPLE);
            gray(source, "停止: 新しい記憶・空間記憶の更新・選好学習・長期の内部状態（trust/attachment など）");
            gray(source, "継続: 文脈の取得・記憶の検索・空間記憶の参照・スコア計算・Softmax 選択・行動の実行");
            gray(source, "一時的な感情（fear/stress）だけは STATIC 条件と同じ規則で反応します");
        } else {
            info(source, "[Demo] Evaluation Mode: OFF（学習を再開しました）", Formatting.YELLOW);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // inspect / memory spatial（読み取り専用。Demo Mode でなくても使える）
    // ------------------------------------------------------------------

    private static int inspect(ServerCommandSource source) throws CommandSyntaxException {
        WolfEntity wolf = target(source);
        WolfAiController controller = tracked(source, wolf);
        if (controller == null) {
            return 0;
        }
        java.util.List<String> lines = WolfInspectReport.lines(WolfAiRegistry.displayLabel(wolf),
                controller.agent(), controller.lastDecision());
        info(source, "=== " + lines.get(0) + " ===", Formatting.GOLD);
        for (String line : lines.subList(1, lines.size())) {
            info(source, line, Formatting.WHITE);
        }
        return 1;
    }

    private static int memorySpatial(ServerCommandSource source) throws CommandSyntaxException {
        WolfEntity wolf = target(source);
        WolfAiController controller = tracked(source, wolf);
        if (controller == null) {
            return 0;
        }
        info(source, "=== Spatial Memory: " + WolfAiRegistry.displayLabel(wolf) + " ===", Formatting.GOLD);
        for (String line : SpatialMemoryReport.lines(controller.agent().spatialMemory(),
                ContextBuilder.currentRegion(wolf))) {
            info(source, line, line.startsWith(" ") ? Formatting.WHITE : Formatting.AQUA);
        }
        return 1;
    }

    /** 既に追跡されている個体のランタイムを返す。表示のために新規作成はしない。 */
    private static WolfAiController tracked(ServerCommandSource source, WolfEntity wolf) {
        if (wolf == null) {
            return null;
        }
        WolfAiController controller = WolfAiRegistry.find(wolf).orElse(null);
        if (controller == null) {
            info(source, WolfAiRegistry.displayLabel(wolf) + " はまだ研究 AI に登録されていません", Formatting.RED);
        }
        return controller;
    }

    // ------------------------------------------------------------------
    // summary / scenario（読み取り専用。Demo Mode でなくても使える）
    // ------------------------------------------------------------------

    private static int summary(ServerCommandSource source) throws CommandSyntaxException {
        WolfEntity wolf = target(source);
        if (wolf == null) {
            return 0;
        }
        info(source, "=== Behavioral Summary: " + WolfAiRegistry.displayLabel(wolf) + " ===", Formatting.GOLD);
        for (String line : BehaviorSummaryReport.lines(BehaviorHistory.SHARED, wolf.getUuidAsString(),
                wolf.getWorld().getTime())) {
            info(source, line, line.startsWith("  ") ? Formatting.WHITE : Formatting.GRAY);
        }
        return 1;
    }

    private static int summaryReset(ServerCommandSource source) throws CommandSyntaxException {
        WolfEntity wolf = target(source);
        if (wolf == null) {
            return 0;
        }
        BehaviorHistory.SHARED.reset(wolf.getUuidAsString());
        info(source, WolfAiRegistry.displayLabel(wolf) + " の行動サマリーの記録を消しました（ここから新しいセッション）",
                Formatting.YELLOW);
        gray(source, "研究ログ（decisions.jsonl）と個体の状態には影響しません");
        return 1;
    }

    private static int scenario(ServerCommandSource source, DemoScenarioGuide.Scenario scenario)
            throws CommandSyntaxException {
        List<String> steps = DemoScenarioGuide.steps(scenario);
        info(source, steps.get(0), Formatting.LIGHT_PURPLE);
        for (String step : steps.subList(1, steps.size())) {
            info(source, step, Formatting.WHITE);
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WolfEntity wolf = DemoTargets.commandTarget(player);
        WolfAiController controller = wolf == null ? null : WolfAiRegistry.find(wolf).orElse(null);
        if (controller != null) {
            for (String line : DemoScenarioGuide.progress(scenario, controller.agent(),
                    ContextBuilder.currentRegion(wolf))) {
                info(source, "(" + WolfAiRegistry.displayLabel(wolf) + ") " + line, Formatting.AQUA);
            }
        }
        if (!DemoMode.isEnabled()) {
            gray(source, "撮影の前に /wolfai demo on を実行してください（ログを本実験データと分けるため）");
        }
        gray(source, "詳しい手順と所要時間の根拠：docs/DEMO_RECORDING.md");
        return 1;
    }

    // ------------------------------------------------------------------
    // select
    // ------------------------------------------------------------------

    private static int select(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WolfEntity wolf = DemoTargets.lookedAt(player);
        if (wolf == null) {
            wolf = DemoTargets.commandTarget(player);
        }
        if (wolf == null) {
            info(source, "対象のオオカミが見つかりません（オオカミを見ながら実行してください）", Formatting.RED);
            return 0;
        }
        DemoTargets.select(player, wolf);
        info(source, "対象を選択しました: " + WolfAiRegistry.displayLabel(wolf), Formatting.GREEN);
        gray(source, "名札で名前（A / B など）を付けると表示で見分けやすくなります");
        return 1;
    }

    private static int selectClear(ServerCommandSource source) throws CommandSyntaxException {
        DemoTargets.select(source.getPlayerOrThrow(), null);
        info(source, "選択を解除しました（以後は見ている個体が対象）", Formatting.YELLOW);
        return 1;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static WolfEntity target(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WolfEntity wolf = DemoTargets.commandTarget(player);
        if (wolf == null) {
            info(source, DemoTargets.hasSelection(player)
                    ? "選択中の個体が近くにロードされていません"
                    : "対象のオオカミが見つかりません（/wolfai select で選択してください）", Formatting.RED);
            return null;
        }
        return wolf;
    }

    /** Snapshot と評価モードは、Human Study のセッション中（Study 2）にも使える。ログは本実験の出力先に残る。 */
    private static boolean requireDemoOrSession(ServerCommandSource source) {
        if (DemoMode.isEnabled() || jp.ac.naist.hai.aiwolf.study.StudySession.current() != null) {
            return true;
        }
        info(source, "このコマンドは Demo Mode 中か、実験セッション中（/wolfai session start）だけ使えます", Formatting.RED);
        return false;
    }

    private static boolean requireDemo(ServerCommandSource source) {
        if (DemoMode.isEnabled()) {
            return true;
        }
        info(source, "このコマンドは Demo Mode 中だけ使えます（/wolfai demo on）", Formatting.RED);
        return false;
    }

    private static boolean requireValidName(ServerCommandSource source, String name) {
        if (DemoSnapshotStore.isValidName(name)) {
            return true;
        }
        info(source, "Snapshot 名は英数字・_・- の 32 文字以内にしてください", Formatting.RED);
        return false;
    }

    private static void info(ServerCommandSource source, String message, Formatting color) {
        source.sendFeedback(() -> Text.literal(message).formatted(color), false);
    }

    private static void gray(ServerCommandSource source, String message) {
        info(source, message, Formatting.GRAY);
    }
}
