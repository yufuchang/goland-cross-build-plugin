package com.github.yufuchang.crossbuild;

import com.goide.sdk.GoSdk;
import com.goide.sdk.GoSdkService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maven-like right-side tool window for selecting Go entry points and target platforms. */
public final class CrossBuildToolWindowFactory implements ToolWindowFactory {
    private static final String PLATFORMS_KEY = "com.yunshangxietong.crossbuild.platforms";
    private static final String GO_PATH_KEY = "com.yunshangxietong.crossbuild.goPath";
    private static final String PLATFORM_COUNTS_KEY = "com.yunshangxietong.crossbuild.platformUseCounts";
    private static final List<String> FALLBACK_PLATFORMS = List.of(
            "linux/amd64", "linux/arm64", "windows/amd64", "darwin/arm64",
            "windows/arm64", "darwin/amd64", "linux/386", "linux/arm", "windows/386");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        View view = new View(project);
        Content content = ContentFactory.getInstance().createContent(view.root, "打包", false);
        toolWindow.getContentManager().addContent(content);
        view.refresh();
    }

    private static final class View {
        private final Project project;
        private final PropertiesComponent settings;
        private final JPanel root = new JPanel(new BorderLayout(5, 5));
        private final JPanel mainChoices = new JPanel();
        private final JPanel platformChoices = new JPanel();
        private final JTextField goPath = new JTextField();
        private final JTextArea log = new JTextArea(7, 30);
        private final JButton buildButton = new JButton("打包选中平台");
        private final JButton allBuildButton = new JButton("全平台打包");
        private final Map<Path, JCheckBox> mains = new LinkedHashMap<>();
        private final Map<String, JCheckBox> platforms = new LinkedHashMap<>();
        private final Map<String, Long> platformUseCounts;
        private boolean building;

        private View(Project project) {
            this.project = project;
            this.settings = PropertiesComponent.getInstance(project);
            this.platformUseCounts = PlatformRanking.parse(settings.getValue(PLATFORM_COUNTS_KEY));
            mainChoices.setLayout(new BoxLayout(mainChoices, BoxLayout.Y_AXIS));
            platformChoices.setLayout(new BoxLayout(platformChoices, BoxLayout.Y_AXIS));
            root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            JPanel header = new JPanel(new BorderLayout(3, 3));
            JPanel goRow = new JPanel(new BorderLayout(3, 3));
            goRow.add(new JLabel("Go 可执行文件"), BorderLayout.NORTH);
            goRow.add(goPath, BorderLayout.CENTER);
            JButton chooseGo = new JButton("…");
            chooseGo.addActionListener(event -> chooseGo());
            goRow.add(chooseGo, BorderLayout.EAST);
            header.add(goRow, BorderLayout.NORTH);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            JButton refreshButton = new JButton("重新扫描");
            refreshButton.addActionListener(event -> refresh());
            JButton selectAll = new JButton("全选平台");
            selectAll.addActionListener(event -> setAllPlatforms(true));
            JButton clear = new JButton("清空平台");
            clear.addActionListener(event -> setAllPlatforms(false));
            buildButton.addActionListener(event -> build());
            allBuildButton.addActionListener(event -> {
                setAllPlatforms(true);
                build();
            });
            actions.add(refreshButton);
            actions.add(selectAll);
            actions.add(clear);
            actions.add(buildButton);
            actions.add(allBuildButton);
            header.add(actions, BorderLayout.CENTER);
            root.add(header, BorderLayout.NORTH);

            JSplitPane choices = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    titledScroll("main.go 入口", mainChoices), titledScroll("目标平台", platformChoices));
            choices.setResizeWeight(0.4);
            root.add(choices, BorderLayout.CENTER);

            log.setEditable(false);
            log.setLineWrap(true);
            log.setWrapStyleWord(true);
            root.add(titledScroll("构建日志", log), BorderLayout.SOUTH);
            goPath.setText(settings.getValue(GO_PATH_KEY, detectGo(project)));
            goPath.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent event) { saveGoPath(); }
            });
        }

        private static JScrollPane titledScroll(String title, java.awt.Component component) {
            JScrollPane pane = new JScrollPane(component);
            pane.setBorder(BorderFactory.createTitledBorder(title));
            return pane;
        }

        private static String detectGo(Project project) {
            try {
                GoSdk sdk = GoSdkService.getInstance(project).getSdk(null);
                if (sdk != null && sdk.getHomePath() != null) {
                    Path executable = Path.of(sdk.getHomePath(), "bin",
                            System.getProperty("os.name").toLowerCase().contains("win") ? "go.exe" : "go");
                    if (java.nio.file.Files.isRegularFile(executable)) return executable.toString();
                }
            } catch (RuntimeException ignored) {
                // PATH remains a useful fallback when no project SDK has been selected yet.
            }
            String goRoot = System.getenv("GOROOT");
            if (goRoot != null) {
                Path executable = Path.of(goRoot, "bin",
                        System.getProperty("os.name").toLowerCase().contains("win") ? "go.exe" : "go");
                if (java.nio.file.Files.isRegularFile(executable)) return executable.toString();
            }
            return "go";
        }

        private void chooseGo() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择 go 或 go.exe");
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                goPath.setText(chooser.getSelectedFile().getAbsolutePath());
                saveGoPath();
                refresh();
            }
        }

        private void saveGoPath() {
            settings.setValue(GO_PATH_KEY, goPath.getText().trim());
        }

        private void refresh() {
            saveGoPath();
            String base = project.getBasePath();
            if (base == null) {
                append("无法确定项目根目录。\n");
                return;
            }
            String go = goPath.getText().trim();
            append("扫描项目中的 main.go 和目标平台…\n");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                List<Path> found;
                List<String> supported;
                String platformError = null;
                try {
                    found = CrossBuildCore.scan(Path.of(base));
                } catch (Exception e) {
                    append("扫描失败：" + e.getMessage() + "\n");
                    return;
                }
                try {
                    supported = CrossBuildCore.platforms(go);
                } catch (Exception e) {
                    supported = FALLBACK_PLATFORMS;
                    platformError = e.getMessage();
                }
                List<String> finalSupported = supported;
                String finalError = platformError;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    updateMains(Path.of(base), found);
                    updatePlatforms(finalSupported);
                    append("找到 " + found.size() + " 个入口、" + finalSupported.size() + " 个平台。\n");
                    if (finalError != null) append("未能读取 Go 平台列表，已显示常用平台：" + finalError + "\n");
                });
            });
        }

        private void updateMains(Path rootPath, List<Path> found) {
            Map<Path, Boolean> previous = new HashMap<>();
            mains.forEach((path, box) -> previous.put(path, box.isSelected()));
            mains.clear();
            mainChoices.removeAll();
            for (Path path : found) {
                String label = rootPath.relativize(path).toString();
                JCheckBox box = new JCheckBox(label.isEmpty() ? "项目根目录" : label,
                        previous.getOrDefault(path, true));
                box.setToolTipText(path.toString());
                mains.put(path, box);
                mainChoices.add(box);
            }
            if (found.isEmpty()) mainChoices.add(new JLabel("没有找到 package main 的 main.go"));
            mainChoices.revalidate();
            mainChoices.repaint();
        }

        private void updatePlatforms(List<String> supported) {
            Set<String> current = new HashSet<>();
            if (!platforms.isEmpty()) {
                platforms.forEach((name, box) -> { if (box.isSelected()) current.add(name); });
            } else {
                String saved = settings.getValue(PLATFORMS_KEY);
                current.addAll(saved == null
                        ? List.of("windows/amd64", "linux/amd64", "darwin/arm64")
                        : List.of(saved.split(",")));
            }
            platforms.clear();
            platformChoices.removeAll();
            for (String name : supported) {
                JCheckBox box = new JCheckBox(name, current.contains(name));
                box.addActionListener(event -> savePlatforms());
                platforms.put(name, box);
            }
            reorderPlatforms();
        }

        private void reorderPlatforms() {
            List<String> ordered = PlatformRanking.order(platforms.keySet(), platformUseCounts);
            Map<String, JCheckBox> reordered = new LinkedHashMap<>();
            platformChoices.removeAll();
            for (String name : ordered) {
                JCheckBox box = platforms.get(name);
                long count = platformUseCounts.getOrDefault(name, 0L);
                box.setText(count == 0 ? name : name + "  ·  使用 " + count + " 次");
                reordered.put(name, box);
                platformChoices.add(box);
            }
            platforms.clear();
            platforms.putAll(reordered);
            platformChoices.revalidate();
            platformChoices.repaint();
        }

        private void setAllPlatforms(boolean selected) {
            platforms.values().forEach(box -> box.setSelected(selected));
            savePlatforms();
        }

        private void savePlatforms() {
            settings.setValue(PLATFORMS_KEY, String.join(",", selectedPlatforms()));
        }

        private List<String> selectedPlatforms() {
            return platforms.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey).toList();
        }

        private void build() {
            if (building) return;
            saveGoPath();
            List<Path> selectedMains = mains.entrySet().stream()
                    .filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList();
            List<String> chosenPlatforms = selectedPlatforms();
            if (selectedMains.isEmpty() || chosenPlatforms.isEmpty()) {
                append("请至少选择一个 main.go 入口和一个目标平台。\n");
                return;
            }
            String base = project.getBasePath();
            if (base == null) return;
            Path rootPath = Path.of(base);
            String go = goPath.getText().trim();
            PlatformRanking.recordSelection(platformUseCounts, chosenPlatforms);
            settings.setValue(PLATFORM_COUNTS_KEY, PlatformRanking.serialize(platformUseCounts));
            reorderPlatforms();
            List<String> selectedPlatforms = selectedPlatforms();
            building = true;
            buildButton.setEnabled(false);
            allBuildButton.setEnabled(false);
            int total = selectedMains.size() * selectedPlatforms.size();
            append("开始构建 " + total + " 个目标，输出目录：" + rootPath.resolve("dist") + "\n");
            new Task.Backgroundable(project, "Go 跨平台打包", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    int completed = 0;
                    int failed = 0;
                    try {
                        for (Path main : selectedMains) {
                            for (String platform : selectedPlatforms) {
                                if (indicator.isCanceled()) return;
                                indicator.setText("构建 " + rootPath.relativize(main) + " → " + platform);
                                append("[" + (completed + 1) + "/" + total + "] "
                                        + rootPath.relativize(main) + " → " + platform + "\n");
                                try {
                                    CrossBuildCore.BuildResult result = CrossBuildCore.build(
                                            rootPath, main, platform, go, indicator::isCanceled);
                                    if (result.success()) {
                                        append("完成：" + result.binary() + "\nZIP：" + result.archive() + "\n");
                                    } else {
                                        failed++;
                                        append("失败：" + result.output() + "\n");
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    append("构建已取消。\n");
                                    return;
                                } catch (Exception e) {
                                    failed++;
                                    append("失败：" + e.getMessage() + "\n");
                                }
                                completed++;
                                indicator.setFraction((double) completed / total);
                            }
                        }
                        append("打包结束：成功 " + (completed - failed) + "，失败 " + failed + "。\n");
                    } finally {
                        SwingUtilities.invokeLater(() -> {
                            building = false;
                            buildButton.setEnabled(true);
                            allBuildButton.setEnabled(true);
                        });
                    }
                }
            }.queue();
        }

        private void append(String message) {
            if (SwingUtilities.isEventDispatchThread()) {
                log.append(message);
                log.setCaretPosition(log.getDocument().getLength());
            } else {
                SwingUtilities.invokeLater(() -> append(message));
            }
        }
    }
}

