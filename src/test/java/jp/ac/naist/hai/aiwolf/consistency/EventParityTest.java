package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * シミュレーションとゲーム内で同じ世界をモデルしているかの検査。
 *
 * <p>事前シミュレーションはパラメータ設定の妥当性を見るために使う。
 * そこで使っている出来事がゲーム内で一度も発生しないなら、シミュレーションは
 * 実験とは別の世界を測っていることになり、そこで得た見立ては実機に当てはまらない。
 * Phase 13 の調査で実際に {@code PLAYER_PROTECTED_WOLF} がこの状態にあった
 * （シミュレーションでは中心的な出来事なのに、ゲーム内では発火経路がなかった）。</p>
 *
 * <p>ソースを読んで判定しているのは、一覧を手で書くと必ず古くなるからである。
 * 新しい出来事を定義して発火経路を書き忘れれば、ここで落ちる。</p>
 */
final class EventParityTest {

    private static final Pattern REFERENCE = Pattern.compile("WolfEventType\\.([A-Z][A-Z0-9_]*)");

    /** ゲーム内の発火経路が書かれているファイル。 */
    private static final List<String> EMITTERS = List.of(
            "src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfEventObserver.java",
            "src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfInteractionHandler.java",
            "src/main/java/jp/ac/naist/hai/aiwolf/event/CombatEpisode.java",
            "src/main/java/jp/ac/naist/hai/aiwolf/runtime/SeparationTracker.java",
            "src/main/java/jp/ac/naist/hai/aiwolf/event/DamageSeverity.java");

    private static final String SIMULATION =
            "src/main/java/jp/ac/naist/hai/aiwolf/simulation/PreExperimentSimulation.java";

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && candidate != null; i++) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("リポジトリのルートが見つからない: "
                + Path.of("").toAbsolutePath());
    }

    private static Set<WolfEventType> referencedIn(String... relativePaths) throws IOException {
        Path root = repositoryRoot();
        Set<WolfEventType> found = EnumSet.noneOf(WolfEventType.class);
        for (String relative : relativePaths) {
            Path file = root.resolve(relative);
            assertTrue(Files.isRegularFile(file), "対象ファイルがない: " + relative);
            Matcher matcher = REFERENCE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                for (WolfEventType type : WolfEventType.values()) {
                    if (type.name().equals(matcher.group(1))) {
                        found.add(type);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("定義済みの出来事はすべてゲーム内で発火経路を持つ")
    void everyDefinedEventCanOccurInGame() throws IOException {
        Set<WolfEventType> emitted = referencedIn(EMITTERS.toArray(String[]::new));

        Set<WolfEventType> missing = EnumSet.allOf(WolfEventType.class);
        missing.removeAll(emitted);
        assertTrue(missing.isEmpty(),
                "ゲーム内で一度も起きない出来事が定義されている: "
                        + missing.stream().map(WolfEventType::id).sorted().toList());
    }

    @Test
    @DisplayName("シミュレーションが使う出来事はすべてゲーム内でも起きる")
    void simulationUsesOnlyObservableEvents() throws IOException {
        Set<WolfEventType> simulated = referencedIn(SIMULATION);
        Set<WolfEventType> emitted = referencedIn(EMITTERS.toArray(String[]::new));

        assertFalse(simulated.isEmpty(), "シミュレーションが出来事を使っていない");
        Set<WolfEventType> simulationOnly = EnumSet.copyOf(simulated);
        simulationOnly.removeAll(emitted);
        assertTrue(simulationOnly.isEmpty(),
                "シミュレーションだけで起きる出来事があると、そこで得た見立ては実機に当てはまらない: "
                        + simulationOnly.stream().map(WolfEventType::id).sorted().toList());
    }

    @Test
    @DisplayName("戦闘系の 3 種は CombatEpisode の判定を通って発火する")
    void combatEventsGoThroughEpisodeResolution() throws IOException {
        Set<WolfEventType> fromEpisode = referencedIn(
                "src/main/java/jp/ac/naist/hai/aiwolf/event/CombatEpisode.java");

        assertTrue(fromEpisode.containsAll(Set.of(WolfEventType.PLAYER_PROTECTED_WOLF,
                WolfEventType.DEFEND_SUCCESS, WolfEventType.PLAYER_AND_WOLF_COMBAT)));
    }

    @Test
    @DisplayName("性格ラベルらしい識別子が製品コードに現れない")
    void noPersonalityLabelsAnywhere() throws IOException {
        Path root = repositoryRoot();
        // テストコードは対象外にしている。壊れた設定ファイルのフォールバックを
        // 検証するために、あえて性格ラベルらしい文字列を入力として使うため。
        List<String> forbidden = List.of("BRAVE", "SHY", "FRIENDLY", "AGGRESSIVE",
                "TIMID", "LOYAL", "PERSONALITY_TYPE", "personalityLabel");

        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        String text;
                        try {
                            text = Files.readString(path, StandardCharsets.UTF_8);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                        return forbidden.stream()
                                .filter(text::contains)
                                .map(label -> path.getFileName() + " -> " + label);
                    })
                    .toList();

            assertTrue(offenders.isEmpty(),
                    "固定の性格ラベルを持ち込んではならない: " + offenders);
        }
    }
}
