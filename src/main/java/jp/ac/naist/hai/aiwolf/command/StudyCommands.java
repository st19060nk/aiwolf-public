package jp.ac.naist.hai.aiwolf.command;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import jp.ac.naist.hai.aiwolf.core.AdaptiveFeatures;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.demo.DemoTargets;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;
import jp.ac.naist.hai.aiwolf.study.MemoryCondition;
import jp.ac.naist.hai.aiwolf.study.SessionLogJson;
import jp.ac.naist.hai.aiwolf.study.StudyAssignment;
import jp.ac.naist.hai.aiwolf.study.StudyPhase;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * Human Study の実験セッションのコマンド（Phase 20）。
 *
 * <pre>
 * /wolfai session start &lt;label&gt;   セッション開始（label は p001-s01 のような実験用 ID。氏名は入れない）
 * /wolfai session stop             セッション終了
 * /wolfai session status           現在のセッションとフェーズ
 * /wolfai phase set &lt;PHASE&gt;       BASELINE / TRAINING / MEMORY_TEST / EVALUATION / FREE_PLAY
 * /wolfai phase clear              フェーズを解除
 * /wolfai assignment set &lt;id&gt;            割付 ID（Phase 23）
 * /wolfai assignment order &lt;順序&gt;         条件の順序（例：adaptive&gt;state_preference）
 * /wolfai assignment status|clear|table
 * /wolfai memory condition on|off           対象個体の記憶だけを ON / OFF（Study 2、Phase 24。セッション中のみ）
 * </pre>
 *
 * <p><strong>ラベル・割付 ID はログにそのまま残る。</strong>
 * DO NOT use participant names, email addresses, student IDs, UUIDs, or other identifying information.
 * Use anonymous labels such as p001-s01.（{@link StudySession#LABEL_PRIVACY_WARNING}）</p>
 *
 * <p>開始・終了・フェーズ変更は {@code sessions.jsonl} に記録される。セッション中の全研究ログには
 * {@code sessionId} と {@code phaseId} が付く。</p>
 */
public final class StudyCommands {

    private StudyCommands() {
        throw new AssertionError("utility class");
    }

    /**
     * {@code /wolfai} の下へセッション関連のサブコマンドを足す。
     *
     * @param root {@code /wolfai} のビルダ
     * @return 同じビルダ
     */
    static LiteralArgumentBuilder<ServerCommandSource> attach(LiteralArgumentBuilder<ServerCommandSource> root) {
        LiteralArgumentBuilder<ServerCommandSource> phaseSet = CommandManager.literal("set");
        for (StudyPhase phase : StudyPhase.values()) {
            phaseSet.then(CommandManager.literal(phase.name()).executes(c -> phase(c.getSource(), phase)));
        }
        return root
                .then(CommandManager.literal("session")
                        .then(CommandManager.literal("start")
                                .then(CommandManager.argument("label", StringArgumentType.word())
                                        .executes(c -> start(c.getSource(), StringArgumentType.getString(c, "label")))))
                        .then(CommandManager.literal("help").executes(c -> help(c.getSource())))
                        .then(CommandManager.literal("stop").executes(c -> stop(c.getSource())))
                        .then(CommandManager.literal("status").executes(c -> status(c.getSource()))))
                .then(CommandManager.literal("phase")
                        .then(phaseSet)
                        .then(CommandManager.literal("clear").executes(c -> phase(c.getSource(), null))))
                .then(CommandManager.literal("assignment")
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("id", StringArgumentType.word())
                                        .executes(c -> assignmentSet(c.getSource(), StringArgumentType.getString(c, "id")))))
                        .then(CommandManager.literal("order")
                                .then(CommandManager.argument("order", StringArgumentType.greedyString())
                                        .executes(c -> assignmentOrder(c.getSource(),
                                                StringArgumentType.getString(c, "order")))))
                        .then(CommandManager.literal("status").executes(c -> assignmentStatus(c.getSource())))
                        .then(CommandManager.literal("table").executes(c -> assignmentTable(c.getSource())))
                        .then(CommandManager.literal("clear").executes(c -> assignmentClear(c.getSource()))))
                .then(CommandManager.literal("memory")
                        .then(CommandManager.literal("condition")
                                .then(CommandManager.literal("on").executes(c -> memoryCondition(c.getSource(), true)))
                                .then(CommandManager.literal("off").executes(c -> memoryCondition(c.getSource(), false)))));
    }

    /** セッション関連コマンドの使い方と、ラベルに個人情報を入れない注意（Phase 25C）。 */
    private static int help(ServerCommandSource source) {
        info(source, "/wolfai session start <label>  例：p001-s01（英数字・_・-、64 文字以内）", Formatting.GOLD);
        info(source, "/wolfai phase set BASELINE|TRAINING|MEMORY_TEST|EVALUATION|FREE_PLAY", Formatting.GOLD);
        info(source, "/wolfai session stop | status", Formatting.GOLD);
        info(source, "/wolfai assignment set <id> | order <a>b> | table | status | clear", Formatting.GOLD);
        error(source, StudySession.LABEL_PRIVACY_WARNING);
        return 1;
    }

    private static int start(ServerCommandSource source, String label) {
        if (StudySession.current() != null) {
            error(source, "セッション " + StudySession.current().label() + " が進行中です（/wolfai session stop）");
            return 0;
        }
        if (!StudySession.isValidLabel(label)) {
            error(source, "ラベルは英数字・_・- の 64 文字以内にしてください（@・空白・UUID の形は使えません）。");
            error(source, StudySession.LABEL_PRIVACY_WARNING);
            return 0;
        }
        long worldTime = worldTime(source.getServer());
        StudySession.Active session = StudySession.start(label, worldTime, System.currentTimeMillis(),
                ZonedDateTime.now());
        ResearchLogger.logSession(SessionLogJson.toJsonLine(SessionLogJson.SESSION_START, session, worldTime,
                System.currentTimeMillis(), startDetails(source)));
        info(source, "セッション開始: " + session.label() + "（" + session.sessionId() + "）", Formatting.GREEN);
        info(source, "版: " + jp.ac.naist.hai.aiwolf.core.BuildInfo.current().buildRevision()
                + "  protocol " + StudySession.protocolVersion(), Formatting.GRAY);
        info(source, "フェーズを設定してください: /wolfai phase set BASELINE|TRAINING|MEMORY_TEST|EVALUATION|FREE_PLAY",
                Formatting.GRAY);
        info(source, "セッション中はプレイヤーとオオカミの関係を 1 秒ごとに player-samples.jsonl へ記録します",
                Formatting.GRAY);
        return 1;
    }

    /** 開始時のメタデータ：割付・新規個体の条件・セッションを始めた実験者の研究オオカミの条件。 */
    private static Map<String, Object> startDetails(ServerCommandSource source) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assignmentId", StudyAssignment.id());
        details.put("conditionOrder", StudyAssignment.orderText());
        details.put("assignmentTableHash", StudyAssignment.tableHash());
        details.put("defaultCondition", WolfAiRegistry.defaultCondition().id());
        if (source.getPlayer() != null) {
            String owner = WolfAiRegistry.anonymousId(source.getPlayer().getUuid());
            StringBuilder wolves = new StringBuilder();
            WolfAiRegistry.all().forEach((uuid, controller) -> {
                if (owner != null && owner.equals(controller.agent().ownerResearchId())) {
                    if (wolves.length() > 0) {
                        wolves.append(';');
                    }
                    wolves.append(WolfAiRegistry.wolfSubjectId(uuid)).append('=')
                            .append(controller.agent().condition().id());
                }
            });
            details.put("ownerSubjectId", owner);
            details.put("researchWolves", wolves.length() == 0 ? null : wolves.toString());
        }
        return details;
    }

    private static int stop(ServerCommandSource source) {
        StudySession.Active session = StudySession.current();
        if (session == null) {
            error(source, "進行中のセッションはありません");
            return 0;
        }
        long worldTime = worldTime(source.getServer());
        endEvaluationIfNeeded(session, worldTime);
        logStop(session, worldTime, "COMMAND");
        StudySession.stop();
        info(source, String.format(Locale.ROOT, "セッション終了: %s（%.1f 分）", session.label(),
                session.elapsedTicks(worldTime) / 1200.0), Formatting.YELLOW);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        StudySession.Active session = StudySession.current();
        if (session == null) {
            info(source, "セッション: なし", Formatting.GOLD);
            return 0;
        }
        long worldTime = worldTime(source.getServer());
        info(source, String.format(Locale.ROOT, "セッション: %s（%s）  フェーズ: %s  経過 %.1f 分",
                session.label(), session.sessionId(), session.phaseId() == null ? "-" : session.phaseId(),
                session.elapsedTicks(worldTime) / 1200.0), Formatting.GOLD);
        return 1;
    }

    private static int phase(ServerCommandSource source, StudyPhase phase) {
        StudySession.Active before = StudySession.current();
        if (before == null) {
            error(source, "先にセッションを開始してください（/wolfai session start <label>）");
            return 0;
        }
        StudySession.Active after = StudySession.setPhase(phase);
        long worldTime = worldTime(source.getServer());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromPhase", before.phaseId());
        details.put("toPhase", after.phaseId());
        ResearchLogger.logSession(SessionLogJson.toJsonLine(SessionLogJson.PHASE_CHANGED, after, worldTime,
                System.currentTimeMillis(), details));
        info(source, "フェーズ: " + (phase == null ? "（解除）" : phase.name()), Formatting.GREEN);
        return 1;
    }

    /**
     * サーバ停止時、進行中のセッションを理由つきで閉じる（再起動後に古いセッションが続かないように）。
     *
     * @param server サーバ
     */
    public static void stopOnShutdown(MinecraftServer server) {
        StudySession.Active session = StudySession.current();
        if (session == null) {
            return;
        }
        endEvaluationIfNeeded(session, worldTime(server));
        logStop(session, worldTime(server), "SERVER_STOPPING");
        StudySession.stop();
    }

    /**
     * セッションで始めた評価モード（学習の凍結）を、セッションの終わりに必ず解く。
     * 凍結したまま通常の育成へ戻ると、その後の経験が学習されない。Demo Mode 中は Demo Mode に任せる。
     */
    private static void endEvaluationIfNeeded(StudySession.Active session, long worldTime) {
        if (!DemoMode.isEvaluationActive() || DemoMode.isEnabled()) {
            return;
        }
        DemoMode.setEvaluationActive(false);
        WolfAiRegistry.applyLearningFreeze(false);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("learningFrozen", false);
        details.put("reason", "SESSION_STOP");
        ResearchLogger.logSession(SessionLogJson.controlLine("EVALUATION_STOPPED", session, worldTime,
                System.currentTimeMillis(), details));
    }

    // ------------------------------------------------------------------
    // assignment（Phase 23）
    // ------------------------------------------------------------------

    private static int assignmentSet(ServerCommandSource source, String id) {
        if (!StudyAssignment.isValidId(id)) {
            error(source, "割付 ID は英数字・_ . - の 64 文字以内にしてください（@・空白・UUID の形は使えません）。");
            error(source, StudySession.LABEL_PRIVACY_WARNING);
            return 0;
        }
        StudyAssignment.setId(id);
        logAssignment(source, "ASSIGNMENT_SET");
        info(source, "割付 ID: " + id, Formatting.GREEN);
        return 1;
    }

    private static int assignmentOrder(ServerCommandSource source, String text) {
        var order = StudyAssignment.parseOrder(text);
        if (order.isEmpty()) {
            error(source, "条件の ID を > か , で区切ってください（例：adaptive>state_preference）。使える ID："
                    + java.util.Arrays.stream(ExperimentalCondition.values()).map(ExperimentalCondition::id).toList());
            return 0;
        }
        StudyAssignment.setOrder(order.get());
        logAssignment(source, "ASSIGNMENT_SET");
        info(source, "条件の順序: " + StudyAssignment.orderText(), Formatting.GREEN);
        return 1;
    }

    private static int assignmentStatus(ServerCommandSource source) {
        info(source, "割付 ID: " + (StudyAssignment.id() == null ? "-" : StudyAssignment.id())
                + "  順序: " + (StudyAssignment.orderText() == null ? "-" : StudyAssignment.orderText())
                + "  割付表: " + (StudyAssignment.tableHash() == null ? "なし" : StudyAssignment.tableHash()),
                Formatting.GOLD);
        return 1;
    }

    private static int assignmentTable(ServerCommandSource source) {
        loadAssignmentTableHash();
        info(source, "割付表 config/" + StudyAssignment.TABLE_FILE_NAME + ": "
                + (StudyAssignment.tableHash() == null ? "見つかりません" : StudyAssignment.tableHash()),
                Formatting.GOLD);
        logAssignment(source, "ASSIGNMENT_TABLE_LOADED");
        return StudyAssignment.tableHash() == null ? 0 : 1;
    }

    private static int assignmentClear(ServerCommandSource source) {
        StudyAssignment.clear();
        logAssignment(source, "ASSIGNMENT_CLEARED");
        info(source, "割付 ID と順序を消しました", Formatting.YELLOW);
        return 1;
    }

    /** 割付表のハッシュを読み直す（サーバ起動時と /wolfai assignment table）。表の中身は記録しない。 */
    public static void loadAssignmentTableHash() {
        try {
            StudyAssignment.setTableHash(StudyAssignment.hashFile(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve(StudyAssignment.TABLE_FILE_NAME)).orElse(null));
        } catch (java.io.IOException exception) {
            StudyAssignment.setTableHash(null);
        }
    }

    private static void logAssignment(ServerCommandSource source, String event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assignmentId", StudyAssignment.id());
        details.put("conditionOrder", StudyAssignment.orderText());
        details.put("assignmentTableHash", StudyAssignment.tableHash());
        ResearchLogger.logSession(SessionLogJson.controlLine(event, StudySession.current(),
                worldTime(source.getServer()), System.currentTimeMillis(), details));
    }

    // ------------------------------------------------------------------
    // memory condition（Study 2、Phase 24）
    // ------------------------------------------------------------------

    private static int memoryCondition(ServerCommandSource source, boolean on) {
        if (StudySession.current() == null && !DemoMode.isEnabled()) {
            error(source, "記憶条件の切り替えは実験セッション中（または Demo Mode 中）だけ使えます");
            return 0;
        }
        var player = source.getPlayer();
        WolfEntity wolf = player == null ? null : DemoTargets.commandTarget(player);
        WolfAiController controller = wolf == null ? null : WolfAiRegistry.find(wolf).orElse(null);
        if (controller == null) {
            error(source, "対象のオオカミが見つかりません（/wolfai select）");
            return 0;
        }
        ExperimentalCondition from = controller.agent().condition();
        var to = MemoryCondition.withMemory(from, on);
        if (to.isEmpty()) {
            error(source, from.id() + " は内部状態を学習しない条件なので、記憶だけの切り替えはできません");
            return 0;
        }
        controller.agent().setCondition(to.get());
        WolfAiRegistry.markDirty();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromCondition", from.id());
        details.put("toCondition", to.get().id());
        details.put("learningFrozen", controller.agent().isLearningFrozen());
        logControl(source, "MEMORY_CONDITION_CHANGED", wolf, details);
        info(source, WolfAiRegistry.displayLabel(wolf) + " の記憶: " + (on ? "ON" : "OFF") + "（" + from.id()
                + " → " + to.get().id() + "）。内部状態・選好・記憶の中身はそのままです", Formatting.GREEN);
        return 1;
    }

    /** 条件名から推測させないよう、有効な機構を項目として明示する。 */
    static void writeFeatures(Map<String, Object> details, AdaptiveFeatures features) {
        details.put("memoryEnabled", features.anyMemory());
        details.put("episodicMemoryEnabled", features.episodicMemory());
        details.put("spatialMemoryEnabled", features.spatialMemory());
        details.put("stateLearningEnabled", features.stateLearning());
        details.put("preferenceLearningEnabled", features.preferenceLearning());
    }

    /**
     * セッションに関わる操作を {@code sessions.jsonl} に記録する（Snapshot・評価モード・記憶条件）。
     * 対象の個体があれば、操作後の条件と有効な機構（memoryEnabled など）を明示する。
     *
     * @param source  コマンドの送り手
     * @param event   操作の種類
     * @param wolf    対象の個体（{@code null} 可）
     * @param details 追加の項目
     */
    public static void logControl(ServerCommandSource source, String event, WolfEntity wolf,
                                  Map<String, Object> details) {
        Map<String, Object> all = new LinkedHashMap<>();
        if (wolf != null) {
            all.put("wolfSubjectId", WolfAiRegistry.wolfSubjectId(wolf.getUuid()));
            WolfAiRegistry.find(wolf).ifPresent(controller -> {
                all.put("experimentalCondition", controller.agent().condition().id());
                writeFeatures(all, controller.agent().condition().features());
            });
        }
        all.putAll(details);
        ResearchLogger.logSession(SessionLogJson.controlLine(event, StudySession.current(),
                worldTime(source.getServer()), System.currentTimeMillis(), all));
    }

    private static void logStop(StudySession.Active session, long worldTime, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("lastPhase", session.phaseId());
        details.put("durationTicks", session.elapsedTicks(worldTime));
        ResearchLogger.logSession(SessionLogJson.toJsonLine(SessionLogJson.SESSION_STOP, session, worldTime,
                System.currentTimeMillis(), details));
    }

    private static long worldTime(MinecraftServer server) {
        return server.getOverworld().getTime();
    }

    private static void info(ServerCommandSource source, String message, Formatting color) {
        source.sendFeedback(() -> Text.literal(message).formatted(color), false);
    }

    private static void error(ServerCommandSource source, String message) {
        info(source, message, Formatting.RED);
    }
}
