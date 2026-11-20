package jp.ac.naist.hai.aiwolf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.entity.passive.WolfEntity;

import jp.ac.naist.hai.aiwolf.command.DemoCommands;
import jp.ac.naist.hai.aiwolf.command.StudyCommands;
import jp.ac.naist.hai.aiwolf.command.WolfAiCommand;
import jp.ac.naist.hai.aiwolf.demo.DebugOverlay;
import jp.ac.naist.hai.aiwolf.config.AiWolfConfig;
import jp.ac.naist.hai.aiwolf.config.AiWolfConfigLoader;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.BuildInfo;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.mc.AiWolfGoal;
import jp.ac.naist.hai.aiwolf.mc.CombatEvents;
import jp.ac.naist.hai.aiwolf.mc.AiAvailabilityMonitor;
import jp.ac.naist.hai.aiwolf.mc.DebugDisplay;
import jp.ac.naist.hai.aiwolf.mc.PlayerStateSampler;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;
import jp.ac.naist.hai.aiwolf.mc.WolfInteractionHandler;
import jp.ac.naist.hai.aiwolf.mixin.MobEntityAccessor;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiPersistentState;
import jp.ac.naist.hai.aiwolf.study.StudyAssignment;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * Fabric のエントリポイント。
 *
 * <p>このクラスは<strong>配線だけ</strong>を担当する。AI のロジックをここへ書いてはならない
 * （God class 化の防止）。意思決定は {@code jp.ac.naist.hai.aiwolf.agent} 以下、
 * ゲームとの橋渡しは {@code jp.ac.naist.hai.aiwolf.mc} 以下にある。</p>
 */
public final class AiWolfMod implements ModInitializer {

    /** MOD ID。fabric.mod.json と一致させること。 */
    public static final String MOD_ID = "aiwolf";

    /** 共通ロガー。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("AIWolfResearch");

    @Override
    public void onInitialize() {
        LOGGER.info("AI Wolf Research mod initialising (parameter set version {}, default condition {})",
                AiWolfParameters.PARAMETER_SET_VERSION,
                AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION.id());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WolfAiCommand.register(dispatcher));

        // テイム済みかどうかに関わらず全てのオオカミへゴールを付ける。ゴール自身が
        // canStart() で isTamed() を確認するため、プレイ中にテイムされた個体も
        // チャンクの再読み込みを待たずにその場で研究 AI の制御下に入る。
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof WolfEntity wolf) {
                attachAiGoal(wolf);
            }
        });

        // hitResult があるのは INTERACT_AT のコールバック。1 回の右クリックで両方が来るので、
        // INTERACT 側だけを処理する（理由は WolfInteractionHandler の Javadoc）。
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                WolfInteractionHandler.onUseEntity(player, world, hand, entity, hitResult != null));

        // 被弾は LivingEntityDamageMixin が観測し、DamageObserver が振り分ける。
        // Fabric の AFTER_DAMAGE は防具の軽減を含まず、致死攻撃では発火しないため、
        // 研究に必要な「実際に失われた耐久」を得られない（理由は Mixin の Javadoc）。
        // AttackEntityCallback も使わない。近接攻撃しか拾えないうえ、
        // Mixin 経路と重なって同じ一撃が二重に記録されるため。
        ServerLivingEntityEvents.AFTER_DEATH.register(CombatEvents::onEntityDeath);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DebugDisplay.tick(server);
            AiAvailabilityMonitor.tick(server);
            PlayerStateSampler.tick(server);
            ResearchLogger.tick(server);
        });

        // ワールドのセーブデータから個体を復元する。getOrCreate 側で作られるより前に
        // 読み込む必要があるため、サーバ開始直後に呼ぶ。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // 設定ファイルの値をワールドの保存値より優先する。実験を通じて同じ
            // participant ID を得るには、ワールドを跨いで salt が同じでなければならない。
            AiWolfConfig config = AiWolfConfigLoader.loadOrCreate();
            WolfAiRegistry.bind(WolfAiPersistentState.get(server));
            WolfAiRegistry.setAnonymitySalt(config.experimentSalt());
            WolfAiRegistry.setExperimentSeed(config.experimentSeed());
            // 新規個体の実験条件は必ずここで反映する。以前はこの 1 行がなく、
            // 設定ファイルに static と書いても新規個体は既定条件で作られていた。
            // 実験条件の取り違えは、そのデータを丸ごと使えなくする。
            WolfAiRegistry.setDefaultCondition(config.defaultCondition());
            // 前の起動のセッションを引き継がない（セッションは永続化しない）。
            StudySession.reset();
            StudySession.setProtocolVersion(config.humanStudyProtocolVersion());
            StudyAssignment.reset();
            StudyCommands.loadAssignmentTableHash();
            ResearchLogger.start(server);
            DemoCommands.start(server);
            // 設定ファイルの値ではなく、実際にレジストリへ入った値を出す。
            // 設定を読めたことと、それが使われることは別の話である。
            LOGGER.info("Experiment metadata applied: seed={}, configured default condition: {}",
                    WolfAiRegistry.experimentSeed(), WolfAiRegistry.defaultCondition().id());
            BuildInfo build = BuildInfo.current();
            LOGGER.info("Build: modVersion={}, buildRevision={}, buildDirty={}, protocolVersion={}",
                    build.modVersion(), build.buildRevision(), build.buildDirty(), StudySession.protocolVersion());
        });

        // 再接続したクライアントはデバッグ表示用のサイドバーを知らないので、表示状態を捨てる。
        // サンプラーの前回位置は、セッションの開始・終了・リセット（サーバの起動・停止、ワールドの切り替え）で消す。
        // 前のセッションの最後の位置から次のセッションの移動量を計算しないため（Phase 25 P0-4）。
        PlayerStateSampler.registerSessionBoundaryReset();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DebugOverlay.forget(handler.getPlayer().getUuid());
            // 再接続後の最初のサンプルは、切断前の位置を基準にしない。
            PlayerStateSampler.forgetOwner(handler.getPlayer().getUuid());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // 進行中のセッションは、停止の理由つきで閉じてから書き出す。
            StudyCommands.stopOnShutdown(server);
            ResearchLogger.shutdown();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // 保存はワールドのセーブ処理が済ませている。ここは別ワールドへ持ち越さないための後始末。
            WolfAiRegistry.clear();
            DebugDisplay.clear();
            // Demo Mode・評価モード・選択・スナップショットのメモリ上の内容は持ち越さない。
            DemoCommands.stop();
            StudySession.reset();
            PlayerStateSampler.clear();
            AiAvailabilityMonitor.clear();
        });
    }

    /**
     * 研究 AI のゴールをオオカミへ追加する。すでに追加済みなら何もしない。
     *
     * <p>Fabric API には既存エンティティへ Goal を足す公式 API がないため、
     * {@link MobEntityAccessor} 経由で {@code goalSelector} を取得している。</p>
     *
     * @param wolf 対象のオオカミ（テイム済みでなくてよい）
     */
    private static void attachAiGoal(WolfEntity wolf) {
        var goalSelector = ((MobEntityAccessor) wolf).aiwolf$getGoalSelector();
        boolean alreadyAttached = goalSelector.getGoals().stream()
                .anyMatch(prioritized -> prioritized.getGoal() instanceof AiWolfGoal);
        if (alreadyAttached) {
            return;
        }
        goalSelector.add(AiWolfGoal.PRIORITY, new AiWolfGoal(wolf));
    }
}
