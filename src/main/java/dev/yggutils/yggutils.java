package dev.yggutils;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class YggUtils {

    private static final String CONFIG_FILE = "YggUtils.yml";

    private static final String META_DIR = "meta";
    private static final String AUTHLIB_PATH = META_DIR + "/authlibinjector.jar";

    private static final String DEFAULT_AUTHSERVER =
            "https://auth.example.com";

    private static final String DEFAULT_SERVERFILE =
            "example.jar";

    private static final String AUTHLIB_URL =
            "https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.7/authlib-injector-1.2.7.jar";

    private static volatile Process runningProcess;

    private static final AtomicBoolean exitRequested =
            new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {

        ensureMetaDir();
        downloadAuthlibIfNeeded();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);

        if (!ensureConfigExists(yaml)) {
            return;
        }

        Map<String, Object> config = loadConfig(yaml);

        String authServer =
                String.valueOf(config.get("authserver")).trim();

        String serverFile =
                String.valueOf(config.get("serverfile")).trim();

        if (DEFAULT_AUTHSERVER.equals(authServer)
                || DEFAULT_SERVERFILE.equals(serverFile)) {

            println("[YggUtils] Configuration contains default values.");
            println("[YggUtils] Please edit " + CONFIG_FILE + ".");
            return;
        }

        if (authServer.isEmpty() || serverFile.isEmpty()) {

            println("[YggUtils] Configuration contains empty values.");
            return;
        }

        if (!new File(serverFile).exists()) {

            println("[YggUtils] Server JAR not found: " + serverFile);
            return;
        }

        startConsoleListener();

        while (!exitRequested.get()) {

            Process process =
                    startServer(serverFile, authServer);

            int exitCode;

            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                exitCode = -1;
            }

            runningProcess = null;

            if (exitCode == 0) {
                break;
            }

            println(
                    "[YggUtils] Server exited with code "
                            + exitCode
                            + "."
            );

            println(
                    "[YggUtils] Restarting in 3 seconds..."
            );

            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static boolean ensureConfigExists(Yaml yaml) {

        File configFile = new File(CONFIG_FILE);

        if (configFile.exists()) {
            return true;
        }

        try {

            Map<String, Object> defaults =
                    new LinkedHashMap<>();

            defaults.put(
                    "authserver",
                    DEFAULT_AUTHSERVER
            );

            defaults.put(
                    "serverfile",
                    DEFAULT_SERVERFILE
            );

            try (FileWriter writer =
                         new FileWriter(configFile)) {

                yaml.dump(defaults, writer);
            }

            println(
                    "[YggUtils] Generated "
                            + CONFIG_FILE
                            + "."
            );

            println(
                    "[YggUtils] Please configure it before starting."
            );

        } catch (IOException e) {

            println(
                    "[YggUtils] Failed to generate config: "
                            + e.getMessage()
            );
        }

        return false;
    }

    private static Map<String, Object> loadConfig(Yaml yaml)
            throws Exception {

        try (FileInputStream fis =
                     new FileInputStream(CONFIG_FILE)) {

            Map<String, Object> loaded =
                    yaml.load(fis);

            if (loaded == null) {

                throw new RuntimeException(
                        "Config parsing returned null"
                );
            }

            return loaded;
        }
    }

    private static void startConsoleListener() {

        Thread thread = new Thread(() -> {

            try (
                    BufferedReader console =
                            new BufferedReader(
                                    new InputStreamReader(
                                            System.in,
                                            StandardCharsets.UTF_8
                                    )
                            )
            ) {

                String line;

                while ((line = console.readLine()) != null) {

                    Process process = runningProcess;

                    if (process == null) {
                        continue;
                    }

                    try {

                        OutputStream os =
                                process.getOutputStream();

                        synchronized (os) {

                            os.write(
                                    (line + "\n")
                                            .getBytes(
                                                    StandardCharsets.UTF_8
                                            )
                            );

                            os.flush();
                        }

                    } catch (IOException ignored) {
                    }
                }

                exitRequested.set(true);

            } catch (IOException ignored) {

                exitRequested.set(true);
            }

        }, "YggUtils-ConsoleForwarder");

        thread.setDaemon(true);
        thread.start();
    }

    private static Process startServer(
            String serverFile,
            String authServer
    ) throws IOException {

        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-javaagent:" + AUTHLIB_PATH + "=" + authServer,
                "-jar",
                serverFile,
                "nogui"
        );

        pb.redirectErrorStream(true);

        Process process = pb.start();

        runningProcess = process;

        Thread outputThread =
                new Thread(
                        () -> pipeServerOutput(process),
                        "YggUtils-ServerOutput"
                );

        outputThread.setDaemon(true);
        outputThread.start();

        return process;
    }

    private static void pipeServerOutput(Process process) {

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;

            while ((line = reader.readLine()) != null) {

                System.out.println(line);
            }

        } catch (IOException ignored) {
        }
    }

    private static void ensureMetaDir() {

        File dir = new File(META_DIR);

        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private static void downloadAuthlibIfNeeded() {

        File jar = new File(AUTHLIB_PATH);

        if (jar.exists() && jar.length() > 0) {
            return;
        }

        try (
                InputStream in =
                        new URL(AUTHLIB_URL).openStream()
        ) {

            Files.copy(
                    in,
                    jar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );

        } catch (IOException e) {

            println(
                    "[YggUtils] Failed to download Authlib Injector: "
                            + e.getMessage()
            );

            System.exit(1);
        }

        if (!jar.exists() || jar.length() == 0) {

            println(
                    "[YggUtils] Authlib Injector download failed."
            );

            System.exit(1);
        }
    }

    private static void println(String text) {
        System.out.println(text);
    }
}