package jp.ac.naist.hai.aiwolf.command;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.export.ResearchExportService;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.log.SignatureLogJson;
import jp.ac.naist.hai.aiwolf.mc.ContextBuilder;
import jp.ac.naist.hai.aiwolf.simulation.BehaviouralSignature;
import jp.ac.naist.hai.aiwolf.simulation.StandardTestBattery;
import jp.ac.naist.hai.aiwolf.mc.DebugDisplay;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;

/**
 * 実験者向けの {@code /wolfai} コマンド群。
 *
 * <p>すべて operator 権限（レベル 2）を要求する。参加者が内部数値を見てしまうと
 * 「性格値を見たから個性を判断した」という交絡が生じ、研究の妥当性が損なわれるためである。</p>
 *
 * <pre>
 * /wolfai status                             近くの自分のオオカミの状態を表示
 * /wolfai debug basic|detailed|off           デバッグ表示（サイドバー）の切り替え（on = basic）
 * /wolfai demo on|off|status|reset           Research Demo Mode（デモ撮影補助）
 * /wolfai snapshot save|restore|list &lt;name&gt; デモ用の Before / After 保存（Demo Mode 中のみ）
 * /wolfai evaluation start|stop              学習を凍結して行動だけを観察する（Demo Mode 中のみ）
 * /wolfai select [clear]                     表示・デモコマンドの対象個体を選ぶ
 * /wolfai inspect                            対象個体の研究状態の要約（UUID は匿名化）
 * /wolfai memory spatial                     対象個体の空間記憶（現在地と近くの危険な場所）
 * /wolfai summary [reset]                    直近 1 分・5 分・セッションの行動の割合と飼い主との平均距離
 * /wolfai demo scenario bonding|danger|spatial|individuality  撮影手順のガイド（結果は作らない）
 * /wolfai reset                              近くのオオカミを初期状態へ戻す
 * /wolfai mode adaptive|state_only|static|state_memory|state_preference|state_episodic|state_spatial
 *                                            新規個体へ割り当てる実験条件を切り替える
 * </pre>
 */
public final class WolfAiCommand {

    /** 必要な権限レベル。 */
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private WolfAiCommand() {
        throw new AssertionError("utility class");
    }

    /**
     * コマンドを登録する。
     *
     * @param dispatcher コマンドディスパッチャ
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("wolfai")
                .requires(source -> source.hasPermissionLevel(REQUIRED_PERMISSION_LEVEL))
                .then(CommandManager.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(CommandManager.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(CommandManager.literal("export")
                        .executes(context -> export(context.getSource())))
                .then(CommandManager.literal("signature")
                        .executes(context -> signature(context.getSource(), "unlabelled"))
                        .then(CommandManager.argument("label", StringArgumentType.word())
                                .executes(context -> signature(context.getSource(),
                                        StringArgumentType.getString(context, "label")))))
                .then(CommandManager.literal("reset")
                        .executes(context -> reset(context.getSource())))
                .then(CommandManager.literal("mode")
                        .then(CommandManager.literal("adaptive")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.ADAPTIVE)))
                        .then(CommandManager.literal("state_only")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.STATE_ONLY)))
                        .then(CommandManager.literal("static")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.STATIC)))
                        // Phase 16 のアブレーション条件
                        .then(CommandManager.literal("state_memory")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.STATE_MEMORY)))
                        .then(CommandManager.literal("state_preference")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.STATE_PREFERENCE)))
                        .then(CommandManager.literal("state_episodic")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.STATE_EPISODIC)))
                        .then(CommandManager.literal("state_spatial")
                                .executes(context -> mode(context.getSource(), ExperimentalCondition.STATE_SPATIAL))));
        // デバッグ表示とデモ撮影補助（demo / debug / snapshot / evaluation / select）。
        dispatcher.register(StudyCommands.attach(DemoCommands.attach(root)));
    }

    private static int status(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DebugDisplay.NearbyWolfSurvey survey = DebugDisplay.surveyNearbyWolves(player);
        WolfEntity wolf = survey.nearestOwned();
        if (wolf == null) {
            reportMiss(source, survey);
            return 0;
        }

        WolfAiController controller = WolfAiRegistry.getOrCreate(wolf);
        BehaviorType behavior = controller.currentBehavior();

        source.sendFeedback(() -> Text.literal("=== AI Wolf " + wolf.getUuidAsString() + " ===")
                .formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                "condition=%s  behaviour=%s  decisions=%d",
                controller.agent().condition().id(),
                behavior == null ? "-" : behavior.id(),
                controller.agent().random().decisionIndex())), false);
        source.sendFeedback(() -> DebugDisplay.describe(wolf), false);
        source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                "memories=%d  regions=%d  seed=%d",
                controller.agent().memoryStore().size(),
                controller.agent().spatialMemory().size(),
                controller.agent().random().seed())).formatted(Formatting.GRAY), false);

        StringBuilder preferences = new StringBuilder("learned preference:");
        for (BehaviorType type : BehaviorType.ALL) {
            preferences.append(String.format(Locale.ROOT, " %s=%+.3f",
                    type.id(), controller.agent().preferences().get(type)));
        }
        source.sendFeedback(() -> Text.literal(preferences.toString()).formatted(Formatting.GRAY), false);
        return 1;
    }

    /**
     * 登録されている全個体を一覧する。近くにいなくても、このワールドで
     * 追跡されている個体がいるかどうかを確認できる。
     */
    private static int list(ServerCommandSource source) {
        Map<UUID, WolfAiController> controllers = WolfAiRegistry.all();
        source.sendFeedback(() -> Text.literal("=== 追跡中の個体: " + controllers.size() + " 匹 ===")
                .formatted(Formatting.GOLD), false);
        if (controllers.isEmpty()) {
            source.sendFeedback(() -> Text.literal(
                    "このワールドではまだ 1 匹も登録されていません。オオカミをテイムしてください。")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        controllers.forEach((uuid, controller) -> source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                "%s  condition=%s  behaviour=%s  decisions=%d  memories=%d  owner=%s",
                uuid, controller.agent().condition().id(),
                controller.currentBehavior() == null ? "-" : controller.currentBehavior().id(),
                controller.agent().random().decisionIndex(),
                controller.agent().memoryStore().size(),
                controller.agent().ownerResearchId() == null ? "-" : controller.agent().ownerResearchId()))
                .formatted(Formatting.GRAY), false));
        return controllers.size();
    }

    /**
     * 近くの自分のオオカミの行動指紋を、標準テストバッテリで測る。
     *
     * <p>測定は個体の状態を一切変更しない。何度測っても、その後の振る舞いに影響しない。</p>
     */
    private static int signature(ServerCommandSource source, String label) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DebugDisplay.NearbyWolfSurvey survey = DebugDisplay.surveyNearbyWolves(player);
        WolfEntity wolf = survey.nearestOwned();
        if (wolf == null) {
            reportMiss(source, survey);
            return 0;
        }

        WolfAiController controller = WolfAiRegistry.getOrCreate(wolf);
        long worldTime = wolf.getWorld().getTime();
        // セッション番号をワールド時刻から取ることで、測定ごとに乱数ストリームが変わり、
        // 同一個体を複数回測ったときの個体内ばらつき（仮説 H2）を評価できる。
        long sessionIndex = worldTime;
        BehaviouralSignature signature = StandardTestBattery.measure(controller.agent(),
                ContextBuilder.currentRegion(wolf), controller.agent().ownerResearchId(),
                worldTime, sessionIndex);

        source.sendFeedback(() -> Text.literal("=== Behavioral Signature (" + label + ") ===")
                .formatted(Formatting.GOLD), false);
        for (BehaviorType behavior : BehaviorType.ALL) {
            double proportion = signature.proportionOf(behavior);
            source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                    "  %-18s %.3f", behavior.id(), proportion))
                    .formatted(proportion >= 0.10 ? Formatting.WHITE : Formatting.DARK_GRAY), false);
        }
        source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                "  mean owner distance %.2f blocks  (n=%d)",
                signature.meanOwnerDistance(), signature.sampleCount()))
                .formatted(Formatting.AQUA), false);

        ResearchLogger.logSignature(SignatureLogJson.toJsonLine(
                controller.agent().wolfId(), controller.agent().ownerResearchId(),
                controller.agent().condition(), label, sessionIndex, worldTime,
                System.currentTimeMillis(), controller.agent().state(), signature));
        source.sendFeedback(() -> Text.literal("signatures.jsonl に記録しました")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    /**
     * 研究データを書き出す。
     */
    private static int export(ServerCommandSource source) {
        try {
            java.nio.file.Path target = ResearchExportService.export(source.getServer());
            source.sendFeedback(() -> Text.literal("研究データを書き出しました: " + target)
                    .formatted(Formatting.GREEN), true);
            source.sendFeedback(() -> Text.literal(
                    "wolves.json / memories.jsonl と、継続記録の decisions.jsonl / events.jsonl があります")
                    .formatted(Formatting.GRAY), false);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendFeedback(() -> Text.literal("書き出しに失敗しました: " + exception.getMessage())
                    .formatted(Formatting.RED), false);
            return 0;
        }
    }

    /**
     * 対象が見つからなかった理由を伝える。原因が切り分けられないメッセージは
     * 実験中の取りこぼしにつながるため、内訳まで出す。
     */
    private static void reportMiss(ServerCommandSource source, DebugDisplay.NearbyWolfSurvey survey) {
        source.sendFeedback(() -> Text.literal(survey.describeMiss()).formatted(Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("あなたの UUID: "
                + (source.getEntity() == null ? "-" : source.getEntity().getUuidAsString()))
                .formatted(Formatting.GRAY), false);
        for (String detail : survey.details()) {
            source.sendFeedback(() -> Text.literal("  " + detail).formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.literal(
                "追跡中の個体を一覧するには /wolfai list を使ってください").formatted(Formatting.GRAY), false);
    }

    private static int reset(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DebugDisplay.NearbyWolfSurvey survey = DebugDisplay.surveyNearbyWolves(player);
        WolfEntity wolf = survey.nearestOwned();
        if (wolf == null) {
            reportMiss(source, survey);
            return 0;
        }
        WolfAiRegistry.reset(wolf);
        source.sendFeedback(() -> Text.literal("オオカミ " + wolf.getUuidAsString()
                + " を初期状態へ戻しました").formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int mode(ServerCommandSource source, ExperimentalCondition condition) {
        WolfAiRegistry.setDefaultCondition(condition);
        source.sendFeedback(() -> Text.literal("新規個体の実験条件を " + condition.id()
                + " に設定しました（既存個体は変わりません）").formatted(Formatting.YELLOW), true);
        return 1;
    }
}
