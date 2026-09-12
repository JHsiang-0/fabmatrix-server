package com.example.farm.tray;

import javax.imageio.ImageIO;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
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
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Pure JDK tray controller. It never controls printers and exiting it leaves the service running. */
public final class FabMatrixTray {
    private static final String SERVICE_NAME = "FabMatrixServer";
    private static final int PORT = 8080;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final int MENU_FONT_SIZE = 12;
    private static final List<String> CJK_FONT_FAMILIES = List.of(
            "Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Noto Sans CJK SC",
            "Noto Sans CJK", "Noto Sans CJK TC", "Noto Sans SC", "Source Han Sans SC",
            "WenQuanYi Zen Hei");
    private static final String[] CHINESE_TRAY_TEXTS = {
            "服务：检查中", "健康：检查中", "刷新", "启动服务", "停止服务", "重启服务",
            "复制首选局域网服务地址", "复制本机服务地址", "打开 files", "打开 logs", "管理员编辑配置",
            "退出托盘（不停止服务）", "运行中", "已停止", "变更中", "未安装", "未知", "就绪", "不可用",
            "服务：", "健康：", "FabMatrix Server | 服务 ", " | 健康 ",
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
        private final Font menuFont;
        private final TrayText text;
        private JMenuItem serviceStatus;
        private JMenuItem healthStatus;
        private JPopupMenu popupMenu;
        private JWindow menuAnchor;
        private AWTEventListener popupDismissListener;
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
            trayIcon = new TrayIcon(createBrandIcon(), text.trayTooltip(ServiceState.UNKNOWN, false));
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
            addAction(popupMenu, text.copyPreferredAddress(), () -> copyText(preferredAddress()));
            addAction(popupMenu, text.copyLocalAddress(), () -> copyText("http://127.0.0.1:" + PORT));
            addAction(popupMenu, text.openFiles(), () -> openDirectory(dataRoot.resolve("files")));
            addAction(popupMenu, text.openLogs(), () -> openDirectory(dataRoot.resolve("logs")));
            addAction(popupMenu, text.editConfig(), this::editConfig);
            popupMenu.addSeparator();
            addAction(popupMenu, text.exitTray(), this::shutdown);
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
            item.addActionListener(event -> action.run());
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

            JWindow anchor = new JWindow();
            anchor.setType(Window.Type.POPUP);
            anchor.setAlwaysOnTop(true);
            anchor.setFocusableWindowState(true);
            anchor.setBounds(menuLocation.x, menuLocation.y, 1, 1);
            menuAnchor = anchor;
            try {
                anchor.setVisible(true);
                anchor.requestFocus();
                popupMenu.show(anchor, 0, 0);
                installPopupDismissListener();
            } catch (RuntimeException error) {
                if (popupMenu.isVisible()) {
                    popupMenu.setVisible(false);
                } else {
                    disposeMenuAnchor();
                }
                throw error;
            }
        }

        private void installPopupDismissListener() {
            requireEdt();
            removePopupDismissListener();
            popupDismissListener = event -> {
                if (event instanceof MouseEvent mouseEvent && mouseEvent.getID() == MouseEvent.MOUSE_PRESSED) {
                    if (!isPopupInteraction(mouseEvent, popupMenu)) {
                        SwingUtilities.invokeLater(this::hidePopupMenu);
                    }
                    return;
                }
                if (event instanceof WindowEvent windowEvent &&
                        (windowEvent.getID() == WindowEvent.WINDOW_DEACTIVATED ||
                                windowEvent.getID() == WindowEvent.WINDOW_LOST_FOCUS) &&
                        !isMenuWindow(windowEvent.getOppositeWindow())) {
                    SwingUtilities.invokeLater(this::hidePopupMenuIfFocusOutside);
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(popupDismissListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
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
            JWindow anchor = menuAnchor;
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
                serviceStatus.setText(text.serviceStatus(service));
                healthStatus.setText(text.healthStatus(healthy));
                if (trayIcon != null) {
                    trayIcon.setToolTip(text.trayTooltip(service, healthy));
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
                        text.confirmStop(), text.dialogTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            executor.execute(() -> {
                try {
                    runElevatedPowerShell("service-control.ps1", "-Action", action);
                    refreshStatus();
                } catch (Exception error) {
                    showError(text.serviceOperationFailed(error.getMessage()));
                }
            });
        }

        private void editConfig() {
            try {
                runElevatedPowerShellCommand(buildElevatedEditorCommand(dataRoot.resolve("config/application.yml")));
            } catch (Exception error) {
                showError(text.editConfigFailed(error.getMessage()));
            }
        }

        private void runElevatedPowerShell(String scriptName, String... arguments) throws Exception {
            Path script = appRoot.resolve("service").resolve(scriptName);
            if (!Files.isRegularFile(script)) {
                throw new IOException(text.missingControlScript(script));
            }
            runElevatedPowerShellCommand(buildElevatedScriptCommand(script, arguments));
        }

        private void runElevatedPowerShellCommand(String command) throws Exception {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    encodePowerShell(command))
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(text.uacHelperTimedOut());
            }
            if (process.exitValue() != 0) throw new IOException(output.trim());
        }

        private void openDirectory(Path directory) {
            try {
                Files.createDirectories(directory);
                Desktop.getDesktop().open(directory.toFile());
            } catch (Exception error) {
                showError(text.openDirectoryFailed(error.getMessage()));
            }
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
        private String copyPreferredAddress() { return chinese ? "复制首选局域网服务地址" : "Copy preferred LAN address"; }
        private String copyLocalAddress() { return chinese ? "复制本机服务地址" : "Copy local service address"; }
        private String openFiles() { return chinese ? "打开 files" : "Open files"; }
        private String openLogs() { return chinese ? "打开 logs" : "Open logs"; }
        private String editConfig() { return chinese ? "管理员编辑配置" : "Edit configuration as administrator"; }
        private String exitTray() { return chinese ? "退出托盘（不停止服务）" : "Exit tray (service keeps running)"; }
        private String serviceStatus(ServiceState state) { return (chinese ? "服务：" : "Service: ") + state.label(chinese); }
        private String healthStatus(boolean healthy) {
            return (chinese ? "健康：" : "Health: ") + (healthy ? (chinese ? "就绪" : "Ready") : (chinese ? "不可用" : "Unavailable"));
        }
        private String trayTooltip(ServiceState state, boolean healthy) {
            return chinese ? "FabMatrix Server | 服务 " + state.label(true) + " | 健康 " + (healthy ? "就绪" : "不可用")
                    : "FabMatrix Server | Service " + state.label(false) + " | Health " + (healthy ? "Ready" : "Unavailable");
        }
        private String dialogTitle() { return "FabMatrix Server"; }
        private String confirmStop() {
            return chinese ? "将通过 Windows 服务控制器正常停止 FabMatrixServer。是否继续？"
                    : "FabMatrixServer will be stopped normally through the Windows Service Controller. Continue?";
        }
        private String trayAddFailed(String reason) { return (chinese ? "无法添加 FabMatrix 托盘图标: " : "Unable to add the FabMatrix tray icon: ") + reason; }
        private String uacHelperTimedOut() { return chinese ? "UAC helper 超时" : "The UAC helper timed out"; }
        private String missingControlScript(Path script) { return (chinese ? "缺少控制脚本: " : "Control script is missing: ") + script; }
        private String serviceOperationFailed(String reason) { return (chinese ? "服务操作失败: " : "Service operation failed: ") + reason; }
        private String editConfigFailed(String reason) { return (chinese ? "打开配置失败: " : "Could not open configuration: ") + reason; }
        private String openDirectoryFailed(String reason) { return (chinese ? "无法打开目录: " : "Could not open directory: ") + reason; }
        private String copied(String value) { return (chinese ? "已复制 " : "Copied ") + value; }
        private String copyAddressFailed(String reason) { return (chinese ? "复制地址失败: " : "Could not copy address: ") + reason; }

        private String[] menuItemTexts() {
            return new String[]{
                    serviceChecking(), healthChecking(), refresh(), startService(), stopService(), restartService(),
                    copyPreferredAddress(), copyLocalAddress(), openFiles(), openLogs(), editConfig(), exitTray()
            };
        }

        private String[] selfTestTexts() {
            String[] texts = {
                    serviceChecking(), healthChecking(), refresh(), startService(), stopService(), restartService(),
                    copyPreferredAddress(), copyLocalAddress(), openFiles(), openLogs(), editConfig(), exitTray(),
                    confirmStop(), trayAddFailed("reason"), uacHelperTimedOut(), missingControlScript(Path.of("script")),
                    serviceOperationFailed("reason"), editConfigFailed("reason"), openDirectoryFailed("reason"),
                    copied("address"), copyAddressFailed("reason")
            };
            String[] allTexts = Arrays.copyOf(texts, texts.length + (ServiceState.values().length * 2) + 2);
            int index = texts.length;
            for (ServiceState state : ServiceState.values()) {
                allTexts[index++] = serviceStatus(state);
                allTexts[index++] = trayTooltip(state, true);
            }
            allTexts[index++] = healthStatus(true);
            allTexts[index] = healthStatus(false);
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

    private static String buildElevatedScriptCommand(Path script, String... arguments) {
        return buildElevationCommand(buildScriptInvocation(script, arguments));
    }

    private static String buildScriptInvocation(Path script, String... arguments) {
        StringBuilder invocation = new StringBuilder("& ").append(quotePowerShell(script.toString()));
        for (String argument : arguments) invocation.append(' ').append(quotePowerShell(argument));
        return invocation.toString();
    }

    private static String buildElevatedEditorCommand(Path configPath) {
        return buildElevationCommand("& 'notepad.exe' " + quotePowerShell(configPath.toString()));
    }

    private static String buildElevationCommand(String innerCommand) {
        String innerEncoded = encodePowerShell(innerCommand);
        return "$p=Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru " +
                "-ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand','" + innerEncoded + "'); exit $p.ExitCode";
    }

    private static void runUacCommandSelfTest() {
        Path script = Paths.get("D:\\Program Files\\Fab'Matrix\\service\\service-control.ps1");
        Path config = Paths.get("D:\\Program Files\\Fab'Matrix\\data\\config\\application.yml");
        String innerScriptCommand = buildScriptInvocation(script, "-Action", "restart");
        String scriptCommand = buildElevatedScriptCommand(script, "-Action", "restart");
        String editorCommand = buildElevatedEditorCommand(config);
        if (!innerScriptCommand.contains("'D:\\Program Files\\Fab''Matrix\\service\\service-control.ps1'") ||
                scriptCommand.contains(script.toString()) || editorCommand.contains(config.toString()) ||
                !new String(Base64.getDecoder().decode(encodePowerShell(scriptCommand)), StandardCharsets.UTF_16LE).equals(scriptCommand) ||
                !new String(Base64.getDecoder().decode(encodePowerShell(editorCommand)), StandardCharsets.UTF_16LE).equals(editorCommand)) {
            throw new IllegalStateException("Encoded UAC command construction failed");
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
