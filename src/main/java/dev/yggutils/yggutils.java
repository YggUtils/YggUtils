package dev.yggutils;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class YggUtils {

    private static final String CONFIG_FILE = "YggUtils.yml";
    private static final String META_DIR = "meta";
    private static final String AUTHLIB_PATH = META_DIR + "/authlibinjector.jar";
    private static final String LOG_FILE = "YggUtils-server.log";

    private static final String AUTHLIB_URL =
            "https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.7/authlib-injector-1.2.7.jar";

    private enum InstallerState { NONE, ASK_AUTHSERVER, ASK_SERVERFILE }

    private static volatile Process runningProcess;
    private static final AtomicBoolean exitRequested = new AtomicBoolean(false);

    private static volatile InstallerState installerState = InstallerState.NONE;
    private static final Map<String, String> installerValues = Collections.synchronizedMap(new HashMap<>());
    private static volatile List<File> discoveredJars = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        println("[YggUtils] Starting YggUtils...");

        ensureMetaDir();
        downloadAuthlibIfNeeded();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);

        boolean configOk = tryLoadConfig(yaml);

        if (!configOk) {
            runRawInstaller(yaml);
            if (exitRequested.get()) {
                println("[YggUtils] Exiting as requested during installer.");
                return;
            }
        }

        Map<String, Object> cfg = loadConfigFromDisk(yaml);
        String authServer = cfg.get("authserver").toString();
        String serverFile = cfg.get("serverfile").toString();

        println("[YggUtils] Using auth server: " + authServer);
        println("[YggUtils] Using server file: " + serverFile);

        startConsoleListener();

        while (!exitRequested.get()) {
            Process server = startServer(serverFile, authServer);
            int code;
            try {
                code = server.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            runningProcess = null;
            println("[YggUtils] Server exited with code: " + code);

            if (code == 0) {
                println("[YggUtils] Clean shutdown detected. Exiting wrapper.");
                break;
            } else {
                println("[YggUtils] Non-zero exit; restarting in 3s...");
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }

        println("[YggUtils] Wrapper terminated.");
    }

    // Installer
    private static void runRawInstaller(Yaml yaml) throws Exception {
        installerState = InstallerState.ASK_AUTHSERVER;
        println("");
        println("[YggUtils] No valid configuration detected. Running interactive installer.");
        println("");

        println("[YggUtils] Enter authentication server URL:");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String auth = null;
            while ((auth = br.readLine()) != null) {
                auth = auth.trim();
                if (!auth.isEmpty()) {
                    installerValues.put("authserver", auth);
                    println("[YggUtils] Set authserver = " + auth);
                    break;
                } else {
                    println("[YggUtils] Empty input. Please enter authentication server URL:");
                }
            }
            if (auth == null) {
                exitRequested.set(true);
                return;
            }

            discoveredJars = listServerJars();
            if (discoveredJars.isEmpty()) {
                println("[YggUtils] No .jar files found.");
                exitRequested.set(true);
                return;
            }

            if (discoveredJars.size() == 1) {
                String only = discoveredJars.get(0).getName();
                installerValues.put("serverfile", only);
                println("[YggUtils] Only one .jar found. Selected: " + only);
            } else {
                println("");
                println("[YggUtils] Select the server JAR:");
                for (int i = 0; i < discoveredJars.size(); i++) {
                    println("  " + (i + 1) + ") " + discoveredJars.get(i).getName());
                }
                println("Enter number:");

                String selLine;
                while ((selLine = br.readLine()) != null) {
                    selLine = selLine.trim();
                    if (selLine.isEmpty()) {
                        println("[YggUtils] Enter a number:");
                        continue;
                    }
                    try {
                        int idx = Integer.parseInt(selLine);
                        if (idx < 1 || idx > discoveredJars.size()) {
                            println("[YggUtils] Invalid selection. Try again.");
                            continue;
                        }
                        String chosen = discoveredJars.get(idx - 1).getName();
                        installerValues.put("serverfile", chosen);
                        println("[YggUtils] Selected serverfile = " + chosen);
                        break;
                    } catch (NumberFormatException nfe) {
                        println("[YggUtils] Invalid number. Try again:");
                    }
                }
                if (selLine == null) {
                    exitRequested.set(true);
                    return;
                }
            }

            Map<String, Object> toSave = new LinkedHashMap<>();
            toSave.put("authserver", installerValues.get("authserver"));
            toSave.put("serverfile", installerValues.get("serverfile"));

            try (FileWriter fw = new FileWriter(CONFIG_FILE)) {
                yaml.dump(toSave, fw);
                println("[YggUtils] Configuration saved to " + CONFIG_FILE);
            } catch (IOException e) {
                println("[YggUtils] Failed to write config: " + e.getMessage());
                exitRequested.set(true);
            }
        }

        installerState = InstallerState.NONE;
    }

    private static boolean tryLoadConfig(Yaml yaml) {
        File cfg = new File(CONFIG_FILE);
        if (!cfg.exists()) return false;

        try (FileInputStream fis = new FileInputStream(cfg)) {
            Map<String, Object> loaded = yaml.load(fis);
            if (loaded == null) return false;

            if (!loaded.containsKey("authserver") || !loaded.containsKey("serverfile")) return false;

            String serverFile = loaded.get("serverfile").toString();
            if (!new File(serverFile).exists()) {
                println("[YggUtils] Config serverfile does not exist: " + serverFile);
                return false;
            }
            return true;
        } catch (Exception e) {
            println("[YggUtils] Failed to parse config: " + e.getMessage());
            return false;
        }
    }

    private static Map<String, Object> loadConfigFromDisk(Yaml yaml) throws Exception {
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            Map<String, Object> loaded = yaml.load(fis);
            if (loaded == null) throw new RuntimeException("Config parsing returned null");
            return loaded;
        }
    }

    // console listener
    private static void startConsoleListener() {
        Thread t = new Thread(() -> {
            try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = console.readLine()) != null) {
                    String cmd = line.trim();
                    if (cmd.isEmpty()) continue;

                    if (installerState != InstallerState.NONE) continue;

                    Process p = runningProcess;
                    if (p == null) {
                        println("[YggUtils] No server running.");
                        continue;
                    }

                    try {
                        OutputStream os = p.getOutputStream();
                        synchronized (os) {
                            os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    } catch (IOException e) {
                        println("[YggUtils] Failed to forward input: " + e.getMessage());
                    }
                }
                exitRequested.set(true);
            } catch (IOException ignored) {
                exitRequested.set(true);
            }
        }, "YggUtils-ConsoleForwarder");

        t.setDaemon(true);
        t.start();
    }

    // server launching
    private static Process startServer(String serverFile, String authServer) throws IOException {
        println("[YggUtils] Launching server...");
        println("[YggUtils] Command: java -javaagent:" + AUTHLIB_PATH + "=" + authServer + " -jar " + serverFile + " nogui");

        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-javaagent:" + AUTHLIB_PATH + "=" + authServer,
                "-jar",
                serverFile,
                "nogui"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        runningProcess = p;

        Thread out = new Thread(() -> pipeServerOutput(p), "YggUtils-ServerOutput");
        out.setDaemon(true);
        out.start();

        return p;
    }

    private static void pipeServerOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter log = new BufferedWriter(new FileWriter(LOG_FILE, true))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String out = "[SERVER] " + line;
                println(out);
                try {
                    log.write(out);
                    log.newLine();
                    log.flush();
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private static List<File> listServerJars() {
        File cwd = new File(".");
        File[] files = cwd.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        List<File> jars = new ArrayList<>();
        if (files == null) return jars;

        File self = getSelfJar();
        for (File f : files) {
            try {
                if (self != null && f.getCanonicalFile().equals(self)) continue;
            } catch (IOException ignored) {}
            jars.add(f);
        }
        jars.sort(Comparator.comparing(File::getName));
        return jars;
    }

    private static File getSelfJar() {
        try {
            String path = YggUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File f = new File(path);
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return f.getCanonicalFile();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void ensureMetaDir() {
        File d = new File(META_DIR);
        if (!d.exists()) d.mkdirs();
    }

    private static void downloadAuthlibIfNeeded() {
    
        File jar = new File(AUTHLIB_PATH);
    
        if (jar.exists()) {
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
    
        if (!jar.exists()) {
    
            println(
                    "[YggUtils] Authlib Injector download failed."
            );
    
            System.exit(1);
        }
    }

    private static void println(String s) {
        System.out.println(s);
    }
    private static void print(String s) {
        System.out.print(s);
    }
}
