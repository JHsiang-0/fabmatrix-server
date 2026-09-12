package com.example.farm.tray;

import javax.swing.JOptionPane;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Pure JDK tray controller. It never controls printers and exiting it leaves the service running. */
public final class FabMatrixTray {
    private static final String SERVICE_NAME = "FabMatrixServer";
    private static final int PORT = 8080;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);

    private FabMatrixTray() {
    }

    public static void main(String[] args) throws Exception {
        if (!SystemTray.isSupported() || GraphicsEnvironment.isHeadless()) {
            System.out.println("FabMatrix tray is unavailable on this desktop; the FabMatrixServer service is unchanged.");
            return;
        }
        try (TrayLock ignored = TrayLock.acquire()) {
            if (ignored == null) {
                return;
            }
            CountDownLatch exit = new CountDownLatch(1);
            Controller controller = new Controller(exit);
            javax.swing.SwingUtilities.invokeLater(controller::start);
            exit.await();
        }
    }

    private static final class Controller {
        private final CountDownLatch exit;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "FabMatrix-tray-status");
            thread.setDaemon(true);
            return thread;
        });
        private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        private final Path appRoot = findAppRoot();
        private final Path dataRoot = findDataRoot(appRoot);
        private final MenuItem serviceStatus = new MenuItem("服务：检查中");
        private final MenuItem healthStatus = new MenuItem("健康：检查中");
        private TrayIcon trayIcon;

        private Controller(CountDownLatch exit) {
            this.exit = exit;
            serviceStatus.setEnabled(false);
            healthStatus.setEnabled(false);
        }

        private void start() {
            PopupMenu menu = new PopupMenu();
            menu.add(serviceStatus);
            menu.add(healthStatus);
            menu.addSeparator();
            addAction(menu, "刷新", event -> refreshStatus());
            addAction(menu, "启动服务", event -> controlService("start", false));
            addAction(menu, "停止服务", event -> controlService("stop", true));
            addAction(menu, "重启服务", event -> controlService("restart", true));
            menu.addSeparator();
            addAction(menu, "复制首选局域网服务地址", event -> copyText(preferredAddress()));
            addAction(menu, "复制本机服务地址", event -> copyText("http://127.0.0.1:" + PORT));
            addAction(menu, "打开 files", event -> openDirectory(dataRoot.resolve("files")));
            addAction(menu, "打开 logs", event -> openDirectory(dataRoot.resolve("logs")));
            addAction(menu, "打开 backups", event -> openDirectory(dataRoot.resolve("backups")));
            addAction(menu, "管理员编辑配置", event -> editConfig());
            menu.addSeparator();
            addAction(menu, "退出托盘（不停止服务）", event -> shutdown());

            trayIcon = new TrayIcon(createBrandIcon(), "FabMatrix Server", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> refreshStatus());
            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception error) {
                System.err.println("无法添加 FabMatrix 托盘图标: " + error.getMessage());
                shutdown();
                return;
            }
            refreshStatus();
            executor.scheduleWithFixedDelay(this::refreshStatus, 5, 5, TimeUnit.SECONDS);
        }

        private void addAction(PopupMenu menu, String label, java.awt.event.ActionListener listener) {
            MenuItem item = new MenuItem(label);
            item.addActionListener(listener);
            menu.add(item);
        }

        private void refreshStatus() {
            ServiceState service = readServiceState();
            boolean healthy = service == ServiceState.RUNNING && isHealthy();
            javax.swing.SwingUtilities.invokeLater(() -> {
                serviceStatus.setLabel("服务：" + service.label);
                healthStatus.setLabel("健康：" + (healthy ? "就绪" : "不可用"));
                if (trayIcon != null) {
                    trayIcon.setToolTip("FabMatrix Server | 服务 " + service.label + " | 健康 " + (healthy ? "就绪" : "不可用"));
                }
            });
        }

        private ServiceState readServiceState() {
            try {
                Process process = new ProcessBuilder("sc.exe", "query", SERVICE_NAME)
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return ServiceState.UNKNOWN;
                }
                if (process.exitValue() != 0 || output.contains("1060")) return ServiceState.NOT_INSTALLED;
                if (output.contains("RUNNING")) return ServiceState.RUNNING;
                if (output.contains("STOPPED")) return ServiceState.STOPPED;
                if (output.contains("PENDING")) return ServiceState.PENDING;
            } catch (Exception ignored) {
                // The next poll will retry; the tray must remain usable when SCM is temporarily unavailable.
            }
            return ServiceState.UNKNOWN;
        }

        private boolean isHealthy() {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/actuator/health"))
                        .timeout(HTTP_TIMEOUT).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && response.body().contains("UP");
            } catch (Exception ignored) {
                return false;
            }
        }

        private void controlService(String action, boolean confirmStop) {
            if (confirmStop) {
                int answer = JOptionPane.showConfirmDialog(null,
                        "将通过 Windows 服务控制器正常停止 FabMatrixServer。是否继续？",
                        "FabMatrix Server", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            executor.execute(() -> {
                try {
                    runElevatedPowerShell("service-control.ps1", "-Action", action);
                    refreshStatus();
                } catch (Exception error) {
                    showError("服务操作失败: " + error.getMessage());
                }
            });
        }

        private void editConfig() {
            try {
                Files.createDirectories(dataRoot.resolve("config"));
                runElevatedPowerShellCommand("$p=Start-Process -FilePath 'notepad.exe' -Verb RunAs -Wait -PassThru -ArgumentList '" +
                        escapePowerShell(dataRoot.resolve("config/application.yml").toString()) + "'; exit $p.ExitCode");
            } catch (Exception error) {
                showError("打开配置失败: " + error.getMessage());
            }
        }

        private void runElevatedPowerShell(String scriptName, String... arguments) throws Exception {
            Path script = appRoot.resolve("service").resolve(scriptName);
            if (!Files.isRegularFile(script)) {
                throw new IOException("缺少控制脚本: " + script);
            }
            List<String> commandArguments = new ArrayList<>();
            commandArguments.add("-NoProfile");
            commandArguments.add("-ExecutionPolicy");
            commandArguments.add("Bypass");
            commandArguments.add("-File");
            commandArguments.add(script.toString());
            Collections.addAll(commandArguments, arguments);
            StringBuilder list = new StringBuilder();
            for (String argument : commandArguments) {
                if (list.length() > 0) list.append(',');
                list.append((char) 39).append(escapePowerShell(argument)).append((char) 39);
            }
            runElevatedPowerShellCommand("$p=Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList @(" +
                    list + "); exit $p.ExitCode");
        }

        private void runElevatedPowerShellCommand(String command) throws Exception {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("UAC helper 超时");
            }
            if (process.exitValue() != 0) throw new IOException(output.trim());
        }

        private void openDirectory(Path directory) {
            try {
                Files.createDirectories(directory);
                Desktop.getDesktop().open(directory.toFile());
            } catch (Exception error) {
                showError("无法打开目录: " + error.getMessage());
            }
        }

        private void copyText(String value) {
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(value), null);
                if (trayIcon != null) trayIcon.displayMessage("FabMatrix Server", "已复制 " + value, TrayIcon.MessageType.INFO);
            } catch (Exception error) {
                showError("复制地址失败: " + error.getMessage());
            }
        }

        private String preferredAddress() {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            return "http://" + address.getHostAddress() + ":" + PORT;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to localhost when the network stack is unavailable.
            }
            return "http://127.0.0.1:" + PORT;
        }

        private void showError(String message) {
            javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message,
                    "FabMatrix Server", JOptionPane.ERROR_MESSAGE));
        }

        private void shutdown() {
            if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
            executor.shutdownNow();
            exit.countDown();
        }
    }

    private enum ServiceState {
        RUNNING("运行中"), STOPPED("已停止"), PENDING("变更中"), NOT_INSTALLED("未安装"), UNKNOWN("未知");

        private final String label;

        ServiceState(String label) {
            this.label = label;
        }
    }

    private static Path findAppRoot() {
        try {
            Path location = Paths.get(FabMatrixTray.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path appDir = Files.isDirectory(location) ? location : location.getParent();
            Path packagedRoot = appDir.getParent();
            if (packagedRoot != null && Files.exists(packagedRoot.resolve("service"))) return packagedRoot;
        } catch (Exception ignored) {
            // Development fallback.
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Path findDataRoot(Path appRoot) {
        String systemDrive = System.getenv("SystemDrive");
        Path appPath = appRoot.toAbsolutePath().normalize();
        if (systemDrive != null && appPath.getRoot() != null &&
                appPath.getRoot().toString().equalsIgnoreCase(systemDrive + "\\")) {
            String programData = System.getenv().getOrDefault("ProgramData", "C:\\ProgramData");
            return Paths.get(programData, "FabMatrix");
        }
        return appPath.resolve("data");
    }

    private static Image createBrandIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(28, 91, 160));
        graphics.fillRoundRect(1, 1, 30, 30, 8, 8);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 15));
        graphics.drawString("FM", 3, 21);
        graphics.dispose();
        return image;
    }

    private static String escapePowerShell(String value) {
        return value.replace("'", "''");
    }

    private static final class TrayLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private TrayLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private static TrayLock acquire() throws IOException {
            String localAppData = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("java.io.tmpdir"));
            Path lockPath = Paths.get(localAppData, "FabMatrix", "tray.lock");
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                return new TrayLock(channel, lock);
            } catch (OverlappingFileLockException error) {
                channel.close();
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            lock.release();
            channel.close();
        }
    }
}
