package com.macrodash;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패키지 의존 규칙 — 바깥(HTTP·설정)에서 안쪽(비즈니스 규칙)으로만 향하게.
 *
 * <pre>
 *   config · web ─▶ feature/&lt;기능&gt; ─▶ read ─▶ store
 *                         │            └──▶ collector
 *                         ├──▶ analytics (java.*만)
 *                         └──▶ support
 * </pre>
 *
 * <p>소스의 {@code import}와 본문에 적힌 전체 이름({@code com.macrodash.x.Y})을 읽어 검사합니다
 * (새 라이브러리 없이). 규칙을 어기면 어느 파일이 무엇을 가져다 쓰는지 목록으로 알려 줍니다.
 * 규칙과 이유는 docs/adr/0001-feature-modules-and-dependency-rules.md에 있습니다.
 */
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src/main/java/com/macrodash");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);
    private static final Pattern INLINE = Pattern.compile("\\bcom\\.macrodash(?:\\.[a-z]\\w*)+\\.[A-Z]\\w*");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    /** 파일 → (패키지, 참조한 이름들) */
    private static final Map<Path, Source> SOURCES = new TreeMap<>();

    record Source(String pkg, String simpleName, Set<String> refs) {
        boolean isController() {
            return simpleName.endsWith("Controller");
        }
    }

    @BeforeAll
    static void readSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                Matcher pkg = PACKAGE.matcher(text);
                assertThat(pkg.find()).as("package 선언 없음: " + file).isTrue();
                Set<String> refs = new TreeSet<>();
                Matcher imports = IMPORT.matcher(text);
                while (imports.find()) {
                    refs.add(imports.group(1));
                }
                // 주석을 뺀 본문에 적힌 전체 이름도 참조로 봅니다(import 없이 쓰는 경우).
                String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                Matcher inline = INLINE.matcher(code);
                while (inline.find()) {
                    refs.add(inline.group());
                }
                String name = file.getFileName().toString().replace(".java", "");
                SOURCES.put(file, new Source(pkg.group(1), name, refs));
            }
        }
        assertThat(SOURCES).as("소스를 찾지 못했습니다(작업 디렉터리가 backend여야 합니다)").isNotEmpty();
    }

    @Test
    @DisplayName("analytics는 java.*만 쓴다 — 스프링·DB·JSON 라이브러리를 모르는 순수 계산")
    void analyticsIsPure() {
        assertThat(violations("com.macrodash.analytics", ref ->
                !ref.startsWith("java.") && !ref.startsWith("com.macrodash.analytics.")))
                .isEmpty();
    }

    @Test
    @DisplayName("store(저장소 접근)는 수집기·설정·읽기 정책·기능을 모른다")
    void storeOnlyTalksToDatabase() {
        assertThat(violations("com.macrodash.store", ref -> startsWithAny(ref,
                "com.macrodash.collector.", "com.macrodash.config.", "com.macrodash.read.",
                "com.macrodash.feature.", "com.macrodash.web.")))
                .isEmpty();
    }

    @Test
    @DisplayName("support·collector·read는 기능(feature)과 HTTP 계층(web)을 모른다")
    void infrastructureDoesNotReachUp() {
        for (String pkg : List.of("com.macrodash.support", "com.macrodash.collector", "com.macrodash.read")) {
            assertThat(violations(pkg, ref -> startsWithAny(ref, "com.macrodash.feature.", "com.macrodash.web.")))
                    .as(pkg).isEmpty();
        }
    }

    @Test
    @DisplayName("컨트롤러는 자기 기능의 서비스와 support·config만 쓴다(저장소·수집기·다른 기능 직접 호출 금지)")
    void controllersGoThroughTheirOwnServices() {
        List<String> found = new ArrayList<>();
        SOURCES.forEach((file, source) -> {
            if (!source.pkg().startsWith("com.macrodash.feature.") || !source.isController()) {
                return;
            }
            for (String ref : source.refs()) {
                if (ref.startsWith("com.macrodash.")
                        && !ref.startsWith(source.pkg() + ".")
                        && !startsWithAny(ref, "com.macrodash.support.", "com.macrodash.config.")) {
                    found.add(source.simpleName() + " → " + ref);
                }
            }
        });
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("기능끼리는 서비스만 가져다 쓰고, 다른 기능의 컨트롤러는 쓰지 않는다")
    void featuresShareServicesNotControllers() {
        List<String> found = new ArrayList<>();
        SOURCES.forEach((file, source) -> {
            if (!source.pkg().startsWith("com.macrodash.feature.")) {
                return;
            }
            for (String ref : source.refs()) {
                if (ref.startsWith("com.macrodash.feature.") && !ref.startsWith(source.pkg() + ".")
                        && ref.endsWith("Controller")) {
                    found.add(source.simpleName() + " → " + ref);
                }
            }
        });
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("기능 사이 의존에 순환이 없다")
    void noCyclesBetweenFeatures() {
        Map<String, Set<String>> graph = new HashMap<>();
        SOURCES.values().forEach(source -> {
            if (!source.pkg().startsWith("com.macrodash.feature.")) {
                return;
            }
            Set<String> edges = graph.computeIfAbsent(source.pkg(), key -> new TreeSet<>());
            for (String ref : source.refs()) {
                if (ref.startsWith("com.macrodash.feature.")) {
                    String target = ref.substring(0, ref.lastIndexOf('.'));
                    if (!target.equals(source.pkg())) {
                        edges.add(target);
                    }
                }
            }
        });
        assertThat(graph).as("기능 패키지를 찾지 못했습니다").isNotEmpty();
        for (String start : graph.keySet()) {
            assertThat(cycleFrom(start, graph, new ArrayList<>(), new HashSet<>())).as("순환").isNull();
        }
    }

    // ------------------------------------------------------------------ 보조

    /** 패키지(하위 포함) 안의 파일 중 {@code bad}에 걸리는 참조 목록. */
    private static List<String> violations(String pkg, java.util.function.Predicate<String> bad) {
        List<String> found = new ArrayList<>();
        SOURCES.values().forEach(source -> {
            if (source.pkg().equals(pkg) || source.pkg().startsWith(pkg + ".")) {
                source.refs().stream().filter(bad).forEach(ref -> found.add(source.simpleName() + " → " + ref));
            }
        });
        return found;
    }

    private static boolean startsWithAny(String ref, String... prefixes) {
        for (String prefix : prefixes) {
            if (ref.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** start에서 출발해 다시 start로 돌아오는 경로가 있으면 그 경로, 없으면 null. */
    private static List<String> cycleFrom(String node, Map<String, Set<String>> graph,
                                          List<String> path, Set<String> seen) {
        path.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (next.equals(path.get(0))) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(next);
                return cycle;
            }
            if (seen.add(next)) {
                List<String> found = cycleFrom(next, graph, path, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        path.remove(path.size() - 1);
        return null;
    }
}
