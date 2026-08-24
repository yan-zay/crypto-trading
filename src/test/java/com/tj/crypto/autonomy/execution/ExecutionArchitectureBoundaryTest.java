package com.tj.crypto.autonomy.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionArchitectureBoundaryTest {
    private static final List<String> FORBIDDEN_LINK_TOKENS = List.of(
            "com/tj/crypto/trading/venue/",
            "com.tj.crypto.trading.venue.",
            "com/tj/crypto/risk/KillSwitch",
            "com.tj.crypto.risk.KillSwitch",
            "com/tj/crypto/admin/application/AuthService",
            "com/tj/crypto/config/properties/CoinglassProperties",
            "com/tj/crypto/observability/alert/WebhookAlertNotificationSink",
            "javax/crypto/Mac",
            "javax/crypto/spec/SecretKeySpec",
            "getApiKey",
            "getSecretKey",
            "getPassphrase",
            "crypto.private-trading",
            "crypto.auth.secret-key",
            "crypto.alerting.webhook-bearer-token",
            "COINGLASS_API_KEY");
    private static final List<String> FORBIDDEN_DYNAMIC_TOKENS = List.of(
            "java/lang/reflect/",
            "java/util/ServiceLoader",
            "org/springframework/context/ApplicationContext",
            "org/springframework/beans/factory/BeanFactory");

    @Test
    void executionKernelDoesNotLinkPrivateVenueKillSwitchOrSecretCarriers()
            throws IOException, URISyntaxException {
        List<Path> classes = productionClasses();
        List<String> violations = scanClassConstants(classes, FORBIDDEN_LINK_TOKENS);

        assertThat(classes).as("compiled production execution classes").isNotEmpty();
        assertThat(violations).isEmpty();
    }

    @Test
    void executionKernelDoesNotUseDynamicDependencyEscapeHatches()
            throws IOException, URISyntaxException {
        List<Path> classes = productionClasses();
        List<String> violations = scanClassConstants(classes, FORBIDDEN_DYNAMIC_TOKENS);
        Path sourceDirectory = Path.of("src", "main", "java", "com", "tj", "crypto",
                "autonomy", "execution");
        Pattern dynamicSource = Pattern.compile(
                "Class\\s*\\.\\s*forName\\s*\\(|\\.\\s*getBean\\s*\\(");
        try (Stream<Path> sources = Files.walk(sourceDirectory)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    if (dynamicSource.matcher(Files.readString(path)).find()) {
                        violations.add(path + " -> dynamic dependency lookup");
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void plannerCannotReachSignerOrExecutorAndDefaultsRemainUnavailable() {
        for (Field field : DeterministicExecutionPlanner.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .isNotEqualTo(IsolatedExecutionSignerPort.class)
                    .isNotEqualTo(IsolatedVenueExecutorPort.class);
        }
        assertThat(new UnavailableExecutionSignerPort().availability().available()).isFalse();
        assertThat(new UnavailableVenueExecutorPort().availability().available()).isFalse();
        assertThat(IsolatedExecutionSignerPort.class.getDeclaredMethods())
                .extracting(method -> method.getName())
                .containsExactly("availability");
        assertThat(IsolatedVenueExecutorPort.class.getDeclaredMethods())
                .extracting(method -> method.getName())
                .containsExactly("availability");
    }

    private static List<Path> productionClasses() throws IOException, URISyntaxException {
        URL codeSource = ExecutionPlanningCode.class.getProtectionDomain()
                .getCodeSource().getLocation();
        assertThat(codeSource).as("production execution code source").isNotNull();
        assertThat(codeSource.getProtocol()).as("class resource protocol").isEqualTo("file");
        Path directory = Path.of(codeSource.toURI()).resolve(
                Path.of("com", "tj", "crypto", "autonomy", "execution"));
        assertThat(directory).as("production execution class directory").isDirectory();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }

    private static List<String> scanClassConstants(List<Path> classes, List<String> tokens)
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path compiledClass : classes) {
            String constants = new String(Files.readAllBytes(compiledClass),
                    StandardCharsets.ISO_8859_1);
            for (String token : tokens) {
                if (constants.contains(token)) {
                    violations.add(compiledClass.getFileName() + " -> " + token);
                }
            }
        }
        return violations;
    }
}
