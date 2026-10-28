package com.example.farm.tray;

import javax.imageio.ImageIO;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Pure JDK tray controller. Plain exit keeps the service running; explicit stop-and-exit uses SCM. */
public final class FabMatrixTray {
    private static final String APPLICATION_ID = "com.jiadong3d.fabmatrix.server";
    private static final String APPLICATION_VERSION = "1.0.0";
    private static final String SERVICE_NAME = "FabMatrixServer";
    private static final int DEFAULT_PORT = 28081;
    private static final String PORT_METADATA_DIRECTORY = "service-state";
    private static final String PORT_METADATA_FILE = "effective-port.txt";
    private static final Pattern SERVER_PORT_PLACEHOLDER =
            Pattern.compile("^\\$\\{\\s*SERVER_PORT\\s*:\\s*(\\d{1,5})\\s*}$");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final int MENU_FONT_SIZE = 12;
    private static final List<String> CJK_FONT_FAMILIES = List.of(
            "Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Noto Sans CJK SC",
            "Noto Sans CJK", "Noto Sans CJK TC", "Noto Sans SC", "Source Han Sans SC",
            "WenQuanYi Zen Hei");
    private static final String[] CHINESE_TRAY_TEXTS = {
            "服务：检查中", "健康：检查中", "刷新", "启动服务", "停止服务", "重启服务",
            "连接与初始化说明", "关闭", "复制首选局域网服务地址", "复制本机服务地址", "打开打印文件目录", "打开日志目录", "管理员编辑配置",
            "本机连接地址", "局域网连接地址", "数据保存位置", "请在独立安装的客户端填写以上地址，服务端不包含前端网页",
            "首次安装按客户端提示创建管理员，已有数据使用原账号登录；初始化入口仅在首次安装时可用",
            "退出托盘（不停止服务）", "停止服务并退出", "所有客户端将断开连接，停止成功后才退出托盘", "运行中", "已停止", "变更中", "未安装", "未知", "就绪", "不可用",
            "服务：", "健康：", "嘉东三维服务器 | 服务 ", " | 健康 ",
            "无法添加 FabMatrix 托盘图标: ", "将通过 Windows 服务控制器正常停止 FabMatrixServer。是否继续？",
            "UAC helper 超时", "缺少控制脚本: ", "服务操作失败: ", "打开配置失败: ",
            "无法打开目录: ", "已复制 ", "复制地址失败: "
    };

    private FabMatrixTray() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test-brand-icon".equals(args[0])) {
            runBrandIconSelfTest();
            return;
        }
        if (args.length == 1 && "--self-test-uac-command".equals(args[0])) {
            runUacCommandSelfTest();
            return;
        }
        if (args.length == 1 && "--self-test-menu-font".equals(args[0])) {
            runMenuFontSelfTest();
            return;
        }
        if (args.length == 1 && "--self-test-popup-focus".equals(args[0])) {
            runPopupFocusSelfTest();
            return;
        }
        if (args.length == 1 && "--self-test-port-config".equals(args[0])) {
            runPortConfigSelfTest();
            return;
        }
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
        private final int port = readEffectivePort(appRoot, dataRoot);
        private final Font menuFont;
        private final TrayText text;
        private JMenuItem serviceStatus;
        private JMenuItem healthStatus;
        private JPopupMenu popupMenu;
        private JDialog menuAnchor;
        private AWTEventListener popupDismissListener;
        private KeyEventDispatcher popupEscapeDispatcher;
        private boolean serviceOperationInProgress;
        private boolean popupGestureHandled;
        private TrayIcon trayIcon;

        private Controller(CountDownLatch exit) {
            this.exit = exit;
            MenuFontSelection selection = selectMenuFont();
            menuFont = selection.font;
            text = new TrayText(selection.supportsChinese);
        }

        private void start() {
            requireEdt();
            initializeMenu();
            trayIcon = new TrayIcon(createBrandIcon(), text.trayTooltip(ServiceState.UNKNOWN, false, port));
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> refreshStatus());
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    handlePopupGesture(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    handlePopupGesture(event);
                }
            });
            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception error) {
                System.err.println(text.trayAddFailed(error.getMessage()));
                shutdown();
                return;
            }
            refreshStatus();
            executor.scheduleWithFixedDelay(this::refreshStatus, 5, 5, TimeUnit.SECONDS);
        }

        private void initializeMenu() {
            requireEdt();
            popupMenu = new JPopupMenu();
            popupMenu.setFont(menuFont);
            popupMenu.setLightWeightPopupEnabled(false);
            serviceStatus = addMenuItem(popupMenu, text.serviceChecking());
            healthStatus = addMenuItem(popupMenu, text.healthChecking());
            serviceStatus.setEnabled(false);
            healthStatus.setEnabled(false);
            popupMenu.addSeparator();
            addAction(popupMenu, text.refresh(), this::refreshStatus);
            addAction(popupMenu, text.startService(), () -> controlService("start", false));
            addAction(popupMenu, text.stopService(), () -> controlService("stop", true));
            addAction(popupMenu, text.restartService(), () -> controlService("restart", true));
            popupMenu.addSeparator();
            addAction(popupMenu, text.connectionGuideTitle(), this::showConnectionGuide);
            addAction(popupMenu, text.copyPreferredAddress(), () -> copyText(preferredAddress()));
            addAction(popupMenu, text.copyLocalAddress(), () -> copyText("http://127.0.0.1:" + port));
            addAction(popupMenu, text.openFiles(), () -> openDirectory(dataRoot.resolve("files")));
            addAction(popupMenu, text.openLogs(), () -> openDirectory(dataRoot.resolve("logs")));
            addAction(popupMenu, text.editConfig(), this::editConfig);
            popupMenu.addSeparator();
            addAction(popupMenu, text.stopAndExit(), this::stopServiceAndExit);
            addAction(popupMenu, text.exitTray(), this::exitTrayOnly);
            popupMenu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                    disposeMenuAnchor();
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent event) {
                    disposeMenuAnchor();
                }
            });
        }

        private JMenuItem addMenuItem(JPopupMenu menu, String label) {
            JMenuItem item = new JMenuItem(label);
            item.setFont(menuFont);
            menu.add(item);
            return item;
        }

        private void addAction(JPopupMenu menu, String label, Runnable action) {
            JMenuItem item = addMenuItem(menu, label);
            item.addActionListener(event -> {
                hidePopupMenu();
                action.run();
            });
        }

        private void handlePopupGesture(MouseEvent event) {
            boolean popupButton = event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event);
            if (!popupButton) return;

            if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                popupGestureHandled = false;
                if (!event.isPopupTrigger()) return;
            }
            if (popupGestureHandled) {
                if (event.getID() == MouseEvent.MOUSE_RELEASED) popupGestureHandled = false;
                return;
            }

            popupGestureHandled = true;
            Point location = new Point(event.getX(), event.getY());
            SwingUtilities.invokeLater(() -> showPopupMenu(location));
            if (event.getID() == MouseEvent.MOUSE_RELEASED) popupGestureHandled = false;
        }

        private void showPopupMenu(Point requestedLocation) {
            requireEdt();
            hidePopupMenu();
            Dimension menuSize = popupMenu.getPreferredSize();
            Point menuLocation = constrainPopupLocation(requestedLocation, menuSize);

            JDialog anchor = createMenuAnchor(menuLocation);
            menuAnchor = anchor;
            try {
                anchor.setVisible(true);
                anchor.toFront();
                popupMenu.show(anchor, 0, 0);
                anchor.requestFocus();
                installPopupDismissListener();
            } catch (RuntimeException error) {
                hidePopupMenu();
                throw error;
            }
        }

        private JDialog createMenuAnchor(Point menuLocation) {
            // A JWindow with no showing Frame/Dialog owner is not focusable,
            // even when setFocusableWindowState(true) was requested.
            JDialog anchor = new JDialog((java.awt.Frame) null);
            anchor.setUndecorated(true);
            anchor.setType(Window.Type.POPUP);
            anchor.setAlwaysOnTop(true);
            anchor.setFocusableWindowState(true);
            anchor.setBounds(menuLocation.x, menuLocation.y, 1, 1);
            return anchor;
        }

        private void installPopupDismissListener() {
            requireEdt();
            removePopupDismissListener();
            JDialog watchedAnchor = menuAnchor;
            popupDismissListener = event -> {
                if (menuAnchor != watchedAnchor) return;
                if (event instanceof MouseEvent mouseEvent && mouseEvent.getID() == MouseEvent.MOUSE_PRESSED) {
                    if (!isPopupInteraction(mouseEvent, popupMenu)) {
                        SwingUtilities.invokeLater(() -> {
                            if (menuAnchor == watchedAnchor) hidePopupMenu();
                        });
                    }
                    return;
                }
                if (event instanceof WindowEvent windowEvent &&
                        isMenuWindow(windowEvent.getWindow()) &&
                        (windowEvent.getID() == WindowEvent.WINDOW_DEACTIVATED ||
                                windowEvent.getID() == WindowEvent.WINDOW_LOST_FOCUS) &&
                        !isMenuWindow(windowEvent.getOppositeWindow())) {
                    SwingUtilities.invokeLater(() -> {
                        if (menuAnchor == watchedAnchor) hidePopupMenuIfFocusOutside();
                    });
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(popupDismissListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
            popupEscapeDispatcher = event -> {
                if (menuAnchor != watchedAnchor || event.getID() != KeyEvent.KEY_PRESSED ||
                        event.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
                hidePopupMenu();
                return true;
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(popupEscapeDispatcher);
        }

        private void hidePopupMenuIfFocusOutside() {
            requireEdt();
            Window focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (!isMenuWindow(focusedWindow)) hidePopupMenu();
        }

        private boolean isMenuWindow(Window window) {
            if (window == null) return false;
            Window popupWindow = popupMenu == null ? null : SwingUtilities.getWindowAncestor(popupMenu);
            return window == menuAnchor || window == popupWindow;
        }

        private void removePopupDismissListener() {
            requireEdt();
            AWTEventListener listener = popupDismissListener;
            popupDismissListener = null;
            if (listener != null) Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            if (popupEscapeDispatcher != null) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(popupEscapeDispatcher);
                popupEscapeDispatcher = null;
            }
        }

        private void hidePopupMenu() {
            requireEdt();
            if (popupMenu != null && popupMenu.isVisible()) {
                popupMenu.setVisible(false);
            } else {
                disposeMenuAnchor();
            }
        }

        private void disposeMenuAnchor() {
            requireEdt();
            removePopupDismissListener();
            JDialog anchor = menuAnchor;
            menuAnchor = null;
            if (anchor != null) {
                anchor.setVisible(false);
                anchor.dispose();
            }
        }

        private void refreshStatus() {
            ServiceState service = readServiceState();
            boolean healthy = service == ServiceState.RUNNING && isHealthy();
            SwingUtilities.invokeLater(() -> {
                serviceStatus.setText(text.serviceStatus(service, port));
                healthStatus.setText(text.healthStatus(healthy, port));
                if (trayIcon != null) {
                    trayIcon.setToolTip(text.trayTooltip(service, healthy, port));
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
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(HTTP_TIMEOUT).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && response.body().contains("UP");
            } catch (Exception ignored) {
                return false;
            }
        }

        private void controlService(String action, boolean confirmStop) {
            if (serviceOperationInProgress) {
                showError(text.serviceBusy());
                return;
            }
            if (confirmStop) {
                int answer = JOptionPane.showConfirmDialog(null,
                        text.confirmStop(), text.dialogTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            serviceOperationInProgress = true;
            executor.execute(() -> {
                try {
                    runElevatedPowerShell("service-control.ps1", action);
                    refreshStatus();
                } catch (Exception error) {
                    showError(text.serviceOperationFailed(error.getMessage()));
                } finally {
                    SwingUtilities.invokeLater(() -> serviceOperationInProgress = false);
                }
            });
        }

        private void stopServiceAndExit() {
            if (serviceOperationInProgress) {
                showError(text.serviceBusy());
                return;
            }
            int answer = JOptionPane.showOptionDialog(null, text.confirmStopAndExit(), text.dialogTitle(),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new String[]{text.stopAndExit(), text.cancel()}, text.cancel());
            if (answer != 0) return;
            serviceOperationInProgress = true;
            executor.execute(() -> {
                try {
                    runStopAndExit(true, () -> {
                        runElevatedPowerShell("service-control.ps1", "stop");
                        if (readServiceState() != ServiceState.STOPPED) throw new IOException(text.serviceDidNotStop());
                    }, () -> SwingUtilities.invokeLater(this::shutdown),
                            error -> showError(text.serviceOperationFailed(error.getMessage())));
                } finally {
                    SwingUtilities.invokeLater(() -> serviceOperationInProgress = false);
                }
            });
        }

        private void exitTrayOnly() {
            if (serviceOperationInProgress) {
                showError(text.serviceBusy());
                return;
            }
            shutdown();
        }

        private void editConfig() {
            try {
                runElevatedPowerShellCommand("& 'notepad.exe' " +
                        quotePowerShell(dataRoot.resolve("config/application.yml").toString()));
            } catch (Exception error) {
                showError(text.editConfigFailed(error.getMessage()));
            }
        }

        private void runElevatedPowerShell(String scriptName, String... arguments) throws Exception {
            Path script = appRoot.resolve("service").resolve(scriptName);
            if (!Files.isRegularFile(script)) {
                throw new IOException(text.missingControlScript(script));
            }
            runElevatedPowerShellCommand(buildScriptInvocation(script, arguments));
        }

        private void runElevatedPowerShellCommand(String innerCommand) throws Exception {
            Path resultPath = createUacResultPath();
            try {
                executeElevatedPowerShell(buildElevationCommand(innerCommand, resultPath), resultPath);
            } finally {
                deleteUacResultPath(resultPath);
            }
        }

        private void executeElevatedPowerShell(String command, Path resultPath) throws Exception {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    encodePowerShell(command))
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(text.uacHelperTimedOut());
            }
            int exitCode = process.exitValue();
            UacResult result = readUacResult(resultPath);
            if (exitCode != 0 || !result.success) {
                throw new IOException(result.message.isBlank() ? text.uacHelperFailed(exitCode) : result.message);
            }
        }

        private void openDirectory(Path directory) {
            try {
                Files.createDirectories(directory);
                Desktop.getDesktop().open(directory.toFile());
            } catch (Exception error) {
                showError(text.openDirectoryFailed(error.getMessage()));
            }
        }

        private void showConnectionGuide() {
            executor.execute(() -> {
                ServiceState service = readServiceState();
                boolean healthy = service == ServiceState.RUNNING && isHealthy();
                String lanAddress = preferredAddress();
                SwingUtilities.invokeLater(() -> {
                    JTextArea details = new JTextArea(text.connectionGuide(service, healthy, port,
                            lanAddress, dataRoot.toString()), 15, 46);
                    details.setEditable(false);
                    details.setLineWrap(true);
                    details.setWrapStyleWord(true);
                    details.setFont(menuFont);
                    details.setCaretPosition(0);
                    int choice = JOptionPane.showOptionDialog(null, new JScrollPane(details),
                            text.connectionGuideTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                            null, new String[]{text.copyPreferredAddress(), text.copyLocalAddress(), text.close()}, text.close());
                    if (choice == 0) copyText(lanAddress);
                    if (choice == 1) copyText("http://127.0.0.1:" + port);
                });
            });
        }

        private void copyText(String value) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(value), null);
                if (trayIcon != null) trayIcon.displayMessage(text.dialogTitle(), text.copied(value), TrayIcon.MessageType.INFO);
            } catch (Exception error) {
                showError(text.copyAddressFailed(error.getMessage()));
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
                            return "http://" + address.getHostAddress() + ":" + port;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to localhost when the network stack is unavailable.
            }
            return "http://127.0.0.1:" + port;
        }

        private void showError(String message) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message,
                    text.dialogTitle(), JOptionPane.ERROR_MESSAGE));
        }

        private void shutdown() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(this::shutdown);
                return;
            }
            hidePopupMenu();
            TrayIcon icon = trayIcon;
            trayIcon = null;
            if (icon != null) SystemTray.getSystemTray().remove(icon);
            executor.shutdownNow();
            exit.countDown();
        }

        private static void requireEdt() {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("Swing tray menu access must run on the EDT");
            }
        }
    }

    @FunctionalInterface
    private interface ServiceStopOperation {
        void stop() throws Exception;
    }

    private static void runStopAndExit(boolean confirmed, ServiceStopOperation stop, Runnable exit,
                                      java.util.function.Consumer<Exception> failure) {
        if (!confirmed) return;
        try {
            stop.stop();
        } catch (Exception error) {
            failure.accept(error);
            return;
        }
        exit.run();
    }

    private static void runPopupFocusSelfTest() throws Exception {
        Controller controller = new Controller(new CountDownLatch(1));
        try {
            // Native window objects are created but never shown or focused.
            SwingUtilities.invokeAndWait(() -> {
                controller.initializeMenu();
                controller.menuAnchor = controller.createMenuAnchor(new Point(0, 0));
                if (!controller.menuAnchor.isFocusableWindow()) {
                    throw new IllegalStateException("Tray menu owner cannot receive native focus");
                }
                controller.installPopupDismissListener();
                controller.popupDismissListener.eventDispatched(new WindowEvent(controller.menuAnchor,
                        WindowEvent.WINDOW_LOST_FOCUS, null));
            });
            SwingUtilities.invokeAndWait(() -> {
                if (controller.menuAnchor != null || controller.popupDismissListener != null ||
                        controller.popupEscapeDispatcher != null) {
                    throw new IllegalStateException("External focus loss did not clean up menu resources");
                }
                controller.menuAnchor = controller.createMenuAnchor(new Point(0, 0));
                controller.installPopupDismissListener();
                if (!controller.popupEscapeDispatcher.dispatchKeyEvent(new KeyEvent(controller.popupMenu,
                        KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, (char) 27)) ||
                        controller.menuAnchor != null || controller.popupEscapeDispatcher != null) {
                    throw new IllegalStateException("Escape did not dismiss the tray menu");
                }
            });
            List<String> events = new ArrayList<>();
            runStopAndExit(false, () -> events.add("stop"), () -> events.add("exit"), error -> events.add("error"));
            if (!events.isEmpty()) throw new IllegalStateException("Cancelled exit controlled the service");
            runStopAndExit(true, () -> { events.add("stop"); throw new IOException("cancelled or failed"); },
                    () -> events.add("exit"), error -> events.add("error"));
            if (!events.equals(List.of("stop", "error"))) throw new IllegalStateException("Failed stop exited the tray");
            events.clear();
            runStopAndExit(true, () -> events.add("stop"), () -> events.add("exit"), error -> events.add("error"));
            if (!events.equals(List.of("stop", "exit"))) throw new IllegalStateException("Exit preceded a confirmed stop");
        } finally {
            SwingUtilities.invokeAndWait(controller::hidePopupMenu);
            controller.executor.shutdownNow();
        }
        System.out.println("FabMatrix tray native focusability, focus-loss/Escape cleanup and safe stop-exit self-test passed.");
    }

    private static Point constrainPopupLocation(Point requestedLocation, Dimension popupSize) {
        Rectangle usableBounds = usableScreenBounds(requestedLocation);
        int maximumX = Math.max(usableBounds.x, usableBounds.x + usableBounds.width - popupSize.width);
        int maximumY = Math.max(usableBounds.y, usableBounds.y + usableBounds.height - popupSize.height);
        int x = Math.max(usableBounds.x, Math.min(requestedLocation.x, maximumX));
        int y = Math.max(usableBounds.y, Math.min(requestedLocation.y, maximumY));
        return new Point(x, y);
    }

    private static boolean isPopupInteraction(AWTEvent event, JPopupMenu menu) {
        if (!(event instanceof MouseEvent mouseEvent) || mouseEvent.getID() != MouseEvent.MOUSE_PRESSED) {
            return false;
        }
        Object source = mouseEvent.getSource();
        return source instanceof Component component &&
                (component == menu || SwingUtilities.isDescendingFrom(component, menu));
    }

    private static Rectangle usableScreenBounds(Point location) {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsConfiguration configuration = environment.getDefaultScreenDevice().getDefaultConfiguration();
        for (GraphicsDevice device : environment.getScreenDevices()) {
            GraphicsConfiguration candidate = device.getDefaultConfiguration();
            if (candidate.getBounds().contains(location)) {
                configuration = candidate;
                break;
            }
        }
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom));
    }

    private enum ServiceState {
        RUNNING("运行中", "Running"), STOPPED("已停止", "Stopped"), PENDING("变更中", "Changing"),
        NOT_INSTALLED("未安装", "Not installed"), UNKNOWN("未知", "Unknown");

        private final String chineseLabel;
        private final String englishLabel;

        ServiceState(String chineseLabel, String englishLabel) {
            this.chineseLabel = chineseLabel;
            this.englishLabel = englishLabel;
        }

        private String label(boolean chinese) {
            return chinese ? chineseLabel : englishLabel;
        }
    }

    private static final class MenuFontSelection {
        private final Font font;
        private final boolean supportsChinese;

        private MenuFontSelection(Font font, boolean supportsChinese) {
            this.font = font;
            this.supportsChinese = supportsChinese;
        }
    }

    private static MenuFontSelection selectMenuFont() {
        Set<String> availableFamilies = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : CJK_FONT_FAMILIES) {
            if (!availableFamilies.contains(family)) continue;
            Font font = new Font(family, Font.PLAIN, MENU_FONT_SIZE);
            if (firstUndisplayable(font, CHINESE_TRAY_TEXTS) == null) {
                return new MenuFontSelection(font, true);
            }
        }
        return new MenuFontSelection(new Font(Font.DIALOG, Font.PLAIN, MENU_FONT_SIZE), false);
    }

    private static String firstUndisplayable(Font font, String[] texts) {
        for (String text : texts) {
            if (font.canDisplayUpTo(text) != -1) return text;
        }
        return null;
    }

    private static boolean containsChinese(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9FFF);
    }

    private static int readEffectivePort(Path appRoot, Path dataRoot) {
        Path metadataPath = appRoot.resolve(PORT_METADATA_DIRECTORY).resolve(PORT_METADATA_FILE);
        Path configPath = dataRoot.resolve("config/application.yml");
        return resolveEffectivePort(metadataPath, System.getenv("SERVER_PORT"), configPath);
    }

    private static int resolveEffectivePort(Path metadataPath, String environmentPort, Path configPath) {
        try {
            Integer installedPort = readPortMetadata(metadataPath);
            if (installedPort != null) return installedPort;
        } catch (IOException ignored) {
            // Missing or unreadable metadata falls through to legacy resolution.
        }
        Integer explicitPort = parsePortValue(environmentPort);
        if (explicitPort != null) return explicitPort;
        try {
            Integer configuredPort = readPortFromConfig(configPath);
            // A stale numeric 8080 is the old product default, not a valid
            // fallback after metadata was lost during repair/reinstall. An
            // explicit SERVER_PORT=8080 was already handled above and remains
            // supported for deployments that intentionally choose it.
            if (configuredPort != null && configuredPort != 8080) return configuredPort;
        } catch (IOException ignored) {
            // A missing or unreadable config must not prevent the tray from starting.
        }
        return DEFAULT_PORT;
    }

    private static Integer readPortMetadata(Path metadataPath) throws IOException {
        if (!Files.isRegularFile(metadataPath)) return null;
        return parsePortValue(Files.readString(metadataPath, StandardCharsets.UTF_8));
    }

    private static Integer readPortFromConfig(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) return null;
        boolean inServerSection = false;
        int serverIndent = -1;
        for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
            String trimmed = line.replace("\uFEFF", "").trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            if (!inServerSection) {
                if (trimmed.matches("server:\\s*(?:#.*)?")) {
                    inServerSection = true;
                    serverIndent = indent;
                }
                continue;
            }
            if (indent <= serverIndent) break;
            int separator = trimmed.indexOf(':');
            if (separator > 0 && "port".equals(trimmed.substring(0, separator).trim())) {
                return parsePortValue(trimmed.substring(separator + 1));
            }
        }
        return null;
    }

    private static Integer parsePortValue(String value) {
        if (value == null) return null;
        String candidate = value.trim().replaceFirst("#.*$", "").trim();
        if (candidate.length() >= 2 &&
                ((candidate.startsWith("'") && candidate.endsWith("'")) ||
                        (candidate.startsWith("\"") && candidate.endsWith("\"")))) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        Matcher placeholder = SERVER_PORT_PLACEHOLDER.matcher(candidate);
        if (placeholder.matches()) candidate = placeholder.group(1);
        if (!candidate.matches("\\d{1,5}")) return null;
        try {
            int port = Integer.parseInt(candidate);
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void runPortConfigSelfTest() throws IOException {
        Path tempDirectory = Files.createTempDirectory("fabmatrix-tray-port-");
        Path metadataPath = tempDirectory.resolve(PORT_METADATA_DIRECTORY).resolve(PORT_METADATA_FILE);
        Path configPath = tempDirectory.resolve("config/application.yml");
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, "server:\n  port: 8080\n", StandardCharsets.UTF_8);
            if (resolveEffectivePort(metadataPath, "", configPath) != DEFAULT_PORT) {
                throw new IllegalStateException("Legacy numeric server.port 8080 was not replaced by the product default");
            }
            Files.writeString(configPath, "server:\n  port: ${SERVER_PORT:8181}\n", StandardCharsets.UTF_8);
            if (resolveEffectivePort(metadataPath, "", configPath) != 8181) {
                throw new IllegalStateException("SERVER_PORT placeholder fallback was not parsed");
            }
            if (resolveEffectivePort(metadataPath, "8282", configPath) != 8282) {
                throw new IllegalStateException("Explicit SERVER_PORT did not take priority");
            }
            Files.createDirectories(metadataPath.getParent());
            Files.writeString(metadataPath, "8383\n", StandardCharsets.UTF_8);
            if (resolveEffectivePort(metadataPath, "8282", configPath) != 8383) {
                throw new IllegalStateException("Installed effective port metadata was not authoritative");
            }
            Files.writeString(metadataPath, "invalid\n", StandardCharsets.UTF_8);
            if (resolveEffectivePort(metadataPath, "invalid", configPath) != 8181) {
                throw new IllegalStateException("Invalid SERVER_PORT did not fall back to config");
            }
            Files.delete(metadataPath);
            Files.delete(configPath);
            if (resolveEffectivePort(metadataPath, "", configPath) != DEFAULT_PORT) {
                throw new IllegalStateException("Missing config did not use the new default port");
            }
            System.out.println("FabMatrix tray effective port metadata self-test passed.");
        } finally {
            Files.deleteIfExists(metadataPath);
            Files.deleteIfExists(metadataPath.getParent());
            Files.deleteIfExists(configPath);
            Files.deleteIfExists(configPath.getParent());
            Files.deleteIfExists(tempDirectory);
        }
    }

    private static void runMenuFontSelfTest() throws Exception {
        Controller controller = new Controller(new CountDownLatch(1));
        try {
            if (controller.text.chinese) {
                String unsupported = firstUndisplayable(controller.menuFont, CHINESE_TRAY_TEXTS);
                if (unsupported != null) {
                    throw new IllegalStateException("Selected tray font cannot display Chinese text: " + unsupported);
                }
            } else {
                String[] fallbackTexts = controller.text.selfTestTexts();
                String unsupported = firstUndisplayable(controller.menuFont, fallbackTexts);
                if (unsupported != null || Arrays.stream(fallbackTexts).anyMatch(FabMatrixTray::containsChinese)) {
                    throw new IllegalStateException("English tray fallback is incomplete or not readable");
                }
            }

            SwingUtilities.invokeAndWait(() -> {
                controller.initializeMenu();
                validateSwingMenuModel(controller.popupMenu, controller.menuFont, controller.text);
            });
        } finally {
            controller.executor.shutdownNow();
        }

        if (controller.text.chinese) {
            System.out.println("FabMatrix Swing tray menu self-test passed with Chinese font: " +
                    controller.menuFont.getFamily());
        } else {
            System.out.println("FabMatrix Swing tray menu self-test passed with readable English fallback.");
        }
    }

    private static void validateSwingMenuModel(JPopupMenu menu, Font expectedFont, TrayText text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Swing tray menu self-test must run on the EDT");
        }
        if (!expectedFont.equals(menu.getFont())) {
            throw new IllegalStateException("Swing popup menu did not retain the selected tray font");
        }

        List<JMenuItem> menuItems = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) menuItems.add(item);
        }
        String[] expectedTexts = text.menuItemTexts();
        if (menuItems.size() != expectedTexts.length) {
            throw new IllegalStateException("Swing tray menu item count is incomplete");
        }
        for (int index = 0; index < expectedTexts.length; index++) {
            JMenuItem item = menuItems.get(index);
            if (!expectedTexts[index].equals(item.getText()) || !expectedFont.equals(item.getFont())) {
                throw new IllegalStateException("Swing tray menu item text or font mismatch at index " + index);
            }
        }
        if (menuItems.get(0).isEnabled() || menuItems.get(1).isEnabled()) {
            throw new IllegalStateException("Swing tray status menu items must remain read-only");
        }

        MouseEvent menuClick = new MouseEvent(menuItems.get(2), MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1);
        MouseEvent outsideClick = new MouseEvent(new JMenuItem("outside"), MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1);
        if (!isPopupInteraction(menuClick, menu) || isPopupInteraction(outsideClick, menu)) {
            throw new IllegalStateException("Swing tray popup click classification is unsafe");
        }
    }

    private static final class TrayText {
        private final boolean chinese;

        private TrayText(boolean chinese) {
            this.chinese = chinese;
        }

        private String serviceChecking() { return chinese ? "服务：检查中" : "Service: checking..."; }
        private String healthChecking() { return chinese ? "健康：检查中" : "Health: checking..."; }
        private String refresh() { return chinese ? "刷新" : "Refresh"; }
        private String startService() { return chinese ? "启动服务" : "Start service"; }
        private String stopService() { return chinese ? "停止服务" : "Stop service"; }
        private String restartService() { return chinese ? "重启服务" : "Restart service"; }
        private String connectionGuideTitle() { return chinese ? "连接与初始化说明" : "Connection and first-time setup"; }
        private String close() { return chinese ? "关闭" : "Close"; }
        private String copyPreferredAddress() { return chinese ? "复制首选局域网服务地址" : "Copy preferred LAN address"; }
        private String copyLocalAddress() { return chinese ? "复制本机服务地址" : "Copy local service address"; }
        private String openFiles() { return chinese ? "打开打印文件目录" : "Open print file folder"; }
        private String openLogs() { return chinese ? "打开日志目录" : "Open log folder"; }
        private String editConfig() { return chinese ? "管理员编辑配置" : "Edit configuration as administrator"; }
        private String exitTray() { return chinese ? "退出托盘（不停止服务）" : "Exit tray (service keeps running)"; }
        private String stopAndExit() { return chinese ? "停止服务并退出" : "Stop service and exit"; }
        private String cancel() { return chinese ? "取消" : "Cancel"; }
        private String serviceBusy() { return chinese ? "服务操作正在进行，请稍后重试。" : "A service operation is in progress. Please try again shortly."; }
        private String serviceDidNotStop() { return chinese ? "未确认服务已停止，托盘将保留。" : "The service has not been confirmed stopped; the tray will stay open."; }
        private String confirmStopAndExit() {
            return chinese ? "所有客户端将断开连接，停止成功后才退出托盘。打印机不会自动停止，期间无法通过本系统查看或控制设备。确定停止服务并退出吗？"
                    : "All clients will disconnect. The tray exits only after the service stops. Printers will not stop automatically, and monitoring and controls will be unavailable. Stop service and exit?";
        }
        private String serviceStatus(ServiceState state, int port) {
            return (chinese ? "服务：" : "Service: ") + state.label(chinese) + (chinese ? "（端口 " : " (port ") + port + (chinese ? "）" : ")");
        }
        private String healthStatus(boolean healthy, int port) {
            return (chinese ? "健康：" : "Health: ") + (healthy ? (chinese ? "就绪" : "Ready") : (chinese ? "不可用" : "Unavailable")) +
                    (chinese ? "（端口 " : " (port ") + port + (chinese ? "）" : ")");
        }
        private String trayTooltip(ServiceState state, boolean healthy, int port) {
            return chinese ? "嘉东三维服务器 v" + APPLICATION_VERSION + " | 服务 " + state.label(true) + " | 健康 " + (healthy ? "就绪" : "不可用") + " | 端口 " + port
                    : "嘉东三维服务器 v" + APPLICATION_VERSION + " | Service " + state.label(false) + " | Health " + (healthy ? "Ready" : "Unavailable") + " | Port " + port;
        }
        private String dialogTitle() { return "嘉东三维服务器"; }
        private String confirmStop() {
            return chinese ? "即将停止或重启服务。此操作不会自动停止打印机，期间无法通过本系统查看或控制设备。是否继续？"
                    : "The service will stop or restart. Printers will not stop automatically, and monitoring and controls will be unavailable meanwhile. Continue?";
        }
        private String connectionGuide(ServiceState state, boolean healthy, int port, String lanAddress, String dataPath) {
            String summary = serviceStatus(state, port) + "\n" + healthStatus(healthy, port) + "\n\n";
            return summary + (chinese
                    ? "本机连接地址：http://127.0.0.1:" + port + "\n局域网连接地址：" + lanAddress +
                    "\n数据保存位置：" + dataPath + "\n\n请在独立安装的 FabMatrix 客户端填写以上地址，服务端不包含前端网页。" +
                    "\n\n首次安装按客户端提示创建管理员，已有数据使用原账号登录。初始化入口仅在首次安装时可用。" +
                    "\n\n若服务未运行，请先选择“启动服务”。局域网无法连接时检查网络和 Windows 专用网络防火墙。退出托盘不会停止服务。"
                    : "Local address: http://127.0.0.1:" + port + "\nLAN address: " + lanAddress +
                    "\nData folder: " + dataPath + "\n\nEnter an address in the separately installed FabMatrix client. The server does not host the client UI." +
                    "\n\nFor a new installation, create the first administrator when prompted by the client. For existing data, sign in with your original account." +
                    "\n\nStart the service if it is stopped. For LAN access problems, check the network and Windows Private firewall. Exiting the tray keeps the service running.");
        }
        private String trayAddFailed(String reason) { return (chinese ? "无法添加 FabMatrix 托盘图标: " : "Unable to add the FabMatrix tray icon: ") + reason; }
        private String uacHelperTimedOut() { return chinese ? "UAC helper 超时" : "The UAC helper timed out"; }
        private String uacHelperFailed(int exitCode) {
            return chinese ? "UAC helper 失败（退出码 " + exitCode + "）" : "The UAC helper failed (exit code " + exitCode + ")";
        }
        private String missingControlScript(Path script) { return (chinese ? "缺少控制脚本: " : "Control script is missing: ") + script; }
        private String serviceOperationFailed(String reason) { return (chinese ? "服务操作失败: " : "Service operation failed: ") + reason; }
        private String editConfigFailed(String reason) { return (chinese ? "打开配置失败: " : "Could not open configuration: ") + reason; }
        private String openDirectoryFailed(String reason) { return (chinese ? "无法打开目录: " : "Could not open directory: ") + reason; }
        private String copied(String value) { return (chinese ? "已复制 " : "Copied ") + value; }
        private String copyAddressFailed(String reason) { return (chinese ? "复制地址失败: " : "Could not copy address: ") + reason; }

        private String[] menuItemTexts() {
            return new String[]{
                    serviceChecking(), healthChecking(), refresh(), startService(), stopService(), restartService(),
                    connectionGuideTitle(), copyPreferredAddress(), copyLocalAddress(), openFiles(), openLogs(), editConfig(), stopAndExit(), exitTray()
            };
        }

        private String[] selfTestTexts() {
            String[] texts = {
                    serviceChecking(), healthChecking(), refresh(), startService(), stopService(), restartService(),
                    copyPreferredAddress(), copyLocalAddress(), openFiles(), openLogs(), editConfig(), exitTray(),
                    confirmStop(), trayAddFailed("reason"), uacHelperTimedOut(), missingControlScript(Path.of("script")),
                    serviceOperationFailed("reason"), editConfigFailed("reason"), openDirectoryFailed("reason"),
                    copied("address"), copyAddressFailed("reason"), connectionGuideTitle(), close(),
                    stopAndExit(), cancel(), serviceBusy(), serviceDidNotStop(), confirmStopAndExit(),
                    connectionGuide(ServiceState.STOPPED, false, 29090, "http://192.168.1.2:29090", "D:/FabMatrix/data")
            };
            String[] allTexts = Arrays.copyOf(texts, texts.length + (ServiceState.values().length * 2) + 2);
            int index = texts.length;
            for (ServiceState state : ServiceState.values()) {
                allTexts[index++] = serviceStatus(state, DEFAULT_PORT);
                allTexts[index++] = trayTooltip(state, true, DEFAULT_PORT);
            }
            allTexts[index++] = healthStatus(true, DEFAULT_PORT);
            allTexts[index] = healthStatus(false, DEFAULT_PORT);
            return allTexts;
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
        return selectBrandIcon(loadPackagedBrandIcon());
    }

    private static Image loadPackagedBrandIcon() {
        try (InputStream resource = FabMatrixTray.class.getResourceAsStream("/tray.png")) {
            if (resource != null) {
                BufferedImage packagedIcon = ImageIO.read(resource);
                if (packagedIcon != null) return packagedIcon;
            }
            System.err.println("FabMatrix tray resource tray.png is unavailable; using the Java2D fallback icon.");
        } catch (Exception error) {
            System.err.println("Unable to load packaged tray.png; using the Java2D fallback icon: " + error.getMessage());
        }
        return null;
    }

    private static Image selectBrandIcon(Image packagedIcon) {
        return packagedIcon != null ? packagedIcon : createFallbackBrandIcon();
    }

    private static Image createFallbackBrandIcon() {
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

    private static void runBrandIconSelfTest() {
        Image packagedIcon = loadPackagedBrandIcon();
        if (packagedIcon == null || packagedIcon.getWidth(null) <= 0 || packagedIcon.getHeight(null) <= 0) {
            throw new IllegalStateException("Packaged tray.png could not be decoded");
        }
        Image fallbackIcon = selectBrandIcon(null);
        if (fallbackIcon.getWidth(null) != 32 || fallbackIcon.getHeight(null) != 32) {
            throw new IllegalStateException("Java2D fallback icon is invalid");
        }
        System.out.println("FabMatrix tray packaged and fallback brand icon self-test passed.");
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String encodePowerShell(String command) {
        return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String buildElevatedScriptCommand(Path script, Path resultPath, String... arguments) {
        return buildElevationCommand(buildScriptInvocation(script, arguments), resultPath);
    }

    private static String buildScriptInvocation(Path script, String... arguments) {
        StringBuilder invocation = new StringBuilder("& ").append(quotePowerShell(script.toString()));
        for (String argument : arguments) invocation.append(' ').append(quotePowerShell(argument));
        return invocation.toString();
    }

    private static String buildElevatedEditorCommand(Path configPath, Path resultPath) {
        return buildElevationCommand("& 'notepad.exe' " + quotePowerShell(configPath.toString()), resultPath);
    }

    private static String buildElevationCommand(String innerCommand, Path resultPath) {
        String resultLiteral = quotePowerShell(resultPath.toString());
        String innerWithResult = "$ProgressPreference='SilentlyContinue';$ErrorActionPreference='Stop';try{" +
                innerCommand + ";" + writeUacResult(resultLiteral, "'OK'") +
                ";exit 0}catch{try{" + writeUacResult(resultLiteral,
                "'ERROR' + [Environment]::NewLine + $_.Exception.Message") + "}catch{};exit 1}";
        String innerEncoded = encodePowerShell(innerWithResult);
        String missingResult = writeUacResult(resultLiteral,
                "'ERROR' + [Environment]::NewLine + 'Elevated helper returned no result (exit code ' + $p.ExitCode + ').'");
        String outerError = writeUacResult(resultLiteral,
                "'ERROR' + [Environment]::NewLine + 'Elevation was canceled or could not be started: ' + $_.Exception.Message");
        return "$ProgressPreference='SilentlyContinue';$ErrorActionPreference='Stop';try{" +
                "$p=Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ErrorAction Stop " +
                "-ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand','" + innerEncoded + "');" +
                "if (-not (Test-Path -LiteralPath " + resultLiteral + ")) {" + missingResult + "};" +
                "exit $p.ExitCode}catch{try{" + outerError + "}catch{};exit 1}";
    }

    private static String writeUacResult(String resultLiteral, String contentsExpression) {
        return "[IO.File]::WriteAllText(" + resultLiteral + "," + contentsExpression +
                ",(New-Object System.Text.UTF8Encoding($false)))";
    }

    private static Path createUacResultPath() throws IOException {
        Path tempDirectory = Files.createTempDirectory(Paths.get(System.getProperty("java.io.tmpdir")),
                "FabMatrix-uac-");
        return tempDirectory.resolve("result.txt");
    }

    private static void deleteUacResultPath(Path resultPath) throws IOException {
        Files.deleteIfExists(resultPath);
        Path resultDirectory = resultPath.getParent();
        if (resultDirectory != null) Files.deleteIfExists(resultDirectory);
    }

    private static UacResult readUacResult(Path resultPath) throws IOException {
        if (!Files.isRegularFile(resultPath)) return new UacResult(false, "");
        String result = Files.readString(resultPath, StandardCharsets.UTF_8);
        int newline = result.indexOf('\n');
        String status = (newline < 0 ? result : result.substring(0, newline)).trim();
        String message = newline < 0 ? "" : result.substring(newline + 1).trim();
        if ("OK".equals(status)) return new UacResult(true, "");
        if (!"ERROR".equals(status)) return new UacResult(false, "");
        return new UacResult(false, sanitizePowerShellError(message));
    }

    private static String sanitizePowerShellError(String value) {
        String message = value == null ? "" : value.trim();
        if (message.contains("#<CLIXML") || message.contains("ProgressRecord")) {
            return "PowerShell returned an unreadable error; CLIXML output was suppressed.";
        }
        return message;
    }

    private static final class UacResult {
        private final boolean success;
        private final String message;

        private UacResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static void runUacCommandSelfTest() throws Exception {
        Path script = Paths.get("D:\\Program Files\\Fab'Matrix\\service\\service-control.ps1");
        Path config = Paths.get("D:\\Program Files\\Fab'Matrix\\data\\config\\application.yml");
        Path resultPath = Paths.get("D:\\Users\\Fab'Matrix\\AppData\\Local\\Temp\\FabMatrix-uac-result.txt");
        String innerScriptCommand = buildScriptInvocation(script, "restart");
        String scriptCommand = buildElevatedScriptCommand(script, resultPath, "restart");
        String editorCommand = buildElevatedEditorCommand(config, resultPath);
        if (!innerScriptCommand.contains("'D:\\Program Files\\Fab''Matrix\\service\\service-control.ps1'") ||
                !innerScriptCommand.endsWith(" 'restart'") || innerScriptCommand.contains("'-Action'") ||
                scriptCommand.contains(script.toString()) || editorCommand.contains(config.toString()) ||
                !scriptCommand.contains("$ProgressPreference='SilentlyContinue'") ||
                !scriptCommand.contains("$ErrorActionPreference='Stop'") ||
                !scriptCommand.contains("Test-Path -LiteralPath") ||
                !new String(Base64.getDecoder().decode(encodePowerShell(scriptCommand)), StandardCharsets.UTF_16LE).equals(scriptCommand) ||
                !new String(Base64.getDecoder().decode(encodePowerShell(editorCommand)), StandardCharsets.UTF_16LE).equals(editorCommand)) {
            throw new IllegalStateException("Encoded UAC command construction failed");
        }

        Path bindingDirectory = Files.createTempDirectory("fabmatrix-tray-binding-");
        Path bindingScript = bindingDirectory.resolve("parameter-binding.ps1");
        try {
            Files.writeString(bindingScript, "param([Parameter(Mandatory=$true)][ValidateSet('start','stop','restart')][string]$Action)\n" +
                    "if ($Action -ne 'start') { throw 'Action binding mismatch' }\n", StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    encodePowerShell(buildScriptInvocation(bindingScript, "start")))
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PowerShell parameter binding self-test timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("PowerShell parameter binding self-test failed: " +
                        sanitizePowerShellError(output));
            }
        } finally {
            Files.deleteIfExists(bindingScript);
            Files.deleteIfExists(bindingDirectory);
        }

        Path resultDirectory = Files.createTempDirectory("fabmatrix-tray-uac-result-");
        Path resultFile = resultDirectory.resolve("result.txt");
        try {
            Files.writeString(resultFile, "ERROR\n#<CLIXML><Objs><Obj S=\"ProgressRecord\" /></Objs>", StandardCharsets.UTF_8);
            String readable = readUacResult(resultFile).message;
            if (readable.contains("#<CLIXML") || readable.contains("ProgressRecord")) {
                throw new IllegalStateException("UAC CLIXML sanitization self-test failed");
            }
        } finally {
            deleteUacResultPath(resultFile);
        }
        System.out.println("FabMatrix tray encoded UAC command self-test passed.");
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
