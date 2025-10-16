// NovaSwingIDECompiler.java
import org.fife.ui.rsyntaxtextarea.*;
import org.fife.ui.rtextarea.*;
import org.fife.ui.autocomplete.*;
import com.google.gson.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.*;
import java.awt.Component;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;

public class NovaSwingIDECompiler extends JFrame {

    private JTabbedPane tabbedPane;
    private JTextArea outputConsole;
    private JButton runButton;
    private File currentDirectory;

    public NovaSwingIDECompiler() {
        setTitle("Nova Swing IDE Compiler");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);

        currentDirectory = new File(System.getProperty("user.dir"));

        // Top-level layout
        setLayout(new BorderLayout());

        // Tabbed editor
        tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // Console
        outputConsole = new JTextArea(10, 80);
        outputConsole.setEditable(false);
        JScrollPane consoleScroll = new JScrollPane(outputConsole);
        add(consoleScroll, BorderLayout.SOUTH);

        // Run Button
        runButton = new JButton("Compile & Run");
        runButton.addActionListener(e -> runCurrentTab());
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(runButton);
        add(topPanel, BorderLayout.NORTH);

        // Menu
        JMenuBar menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        // File Menu
        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        JMenuItem newFile = new JMenuItem("New");
        newFile.addActionListener(e -> createNewTab());
        fileMenu.add(newFile);

        JMenuItem openFile = new JMenuItem("Open");
        openFile.addActionListener(e -> openFile());
        fileMenu.add(openFile);

        JMenuItem saveFile = new JMenuItem("Save");
        saveFile.addActionListener(e -> saveCurrentTab());
        fileMenu.add(saveFile);

        JMenuItem saveAsFile = new JMenuItem("Save As");
        saveAsFile.addActionListener(e -> saveCurrentTabAs());
        fileMenu.add(saveAsFile);

        JMenuItem closeTab = new JMenuItem("Close Tab");
        closeTab.addActionListener(e -> closeCurrentTab());
        fileMenu.add(closeTab);

        // View Menu
        JMenu viewMenu = new JMenu("View");
        menuBar.add(viewMenu);
        JMenuItem showJavaCode = new JMenuItem("Show Generated Java Code");
        showJavaCode.addActionListener(e -> showGeneratedJava());
        viewMenu.add(showJavaCode);

        // Run Menu
        JMenu runMenu = new JMenu("Run");
        menuBar.add(runMenu);
        JMenuItem clearOutput = new JMenuItem("Clear Output");
        clearOutput.addActionListener(e -> outputConsole.setText(""));
        runMenu.add(clearOutput);

        JMenuItem saveJavaOutput = new JMenuItem("Save Java Output");
        saveJavaOutput.addActionListener(e -> saveGeneratedJavaFromTab());
        runMenu.add(saveJavaOutput);

        // Initialize with one tab
        createNewTab();

        setVisible(true);
    }

    private void createNewTab() {
        RSyntaxTextArea editor = new RSyntaxTextArea(20, 80);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        editor.setCodeFoldingEnabled(true);
        RTextScrollPane sp = new RTextScrollPane(editor);
        tabbedPane.addTab("Untitled", sp);
        tabbedPane.setSelectedComponent(sp);
    }

    private RSyntaxTextArea getCurrentEditor() {
        Component comp = tabbedPane.getSelectedComponent();
        if (comp instanceof JScrollPane) {
            JScrollPane scroll = (JScrollPane) comp;
            JViewport vp = scroll.getViewport();
            Component view = vp.getView();
            if (view instanceof RSyntaxTextArea) {
                return (RSyntaxTextArea) view;
            }
        }
        return null;
    }

    private void runCurrentTab() {
        RSyntaxTextArea editor = getCurrentEditor();
        if (editor == null) return;

        String javaCode = convertPseudoToJava(editor.getText());

        // Wrap method main into static main
        javaCode = wrapMain(javaCode);

        String className = "Main";
        saveGeneratedJava(className, javaCode);
        runGeneratedJava(className);
    }

    private String wrapMain(String javaCode) {
        if (javaCode.contains("public void main()")) {
            StringBuilder sb = new StringBuilder();
            sb.append("public class Main {\n");
            sb.append("    public static void main(String[] args) {\n");
            sb.append("        new Main().main();\n");
            sb.append("    }\n");
            for (String line : javaCode.split("\n")) {
                sb.append("    ").append(line).append("\n");
            }
            sb.append("}");
            return sb.toString();
        } else {
            return "public class Main {\n" + javaCode + "\n}";
        }
    }

    private String convertPseudoToJava(String pseudo) {
        StringBuilder javaCode = new StringBuilder();
        try {
            Gson gson = new Gson();
            Reader reader = new FileReader(new File(currentDirectory, "rules.json"));
            List<Map<String, Object>> rules = gson.fromJson(reader, List.class);

            for (String line : pseudo.split("\n")) {
                line = line.trim();
                boolean matched = false;

                if (line.matches(".*;|.*\\{|.*\\}")) {
                    javaCode.append(line).append("\n");
                    continue;
                }

                for (Map<String, Object> rule : rules) {
                    List<String> patterns = (List<String>) rule.get("patterns");
                    String template = (String) rule.get("template");
                    for (String pattern : patterns) {
                        if (line.matches(pattern)) {
                            javaCode.append(applyTemplate(line, pattern, template)).append("\n");
                            matched = true;
                            break;
                        }
                    }
                    if (matched) break;
                }

                if (!matched) {
                    javaCode.append("// Unrecognized pseudocode: ").append(line).append("\n");
                }
            }

        } catch (Exception e) {
            outputConsole.append("Error converting pseudocode: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
        return javaCode.toString();
    }

    private String applyTemplate(String line, String pattern, String template) {
        try {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(line);
            if (m.find()) {
                String result = template;
                for (int i = 1; i <= m.groupCount(); i++) {
                    result = result.replaceFirst("\\$1", Matcher.quoteReplacement(m.group(i)));
                    result = result.replaceFirst("%s", Matcher.quoteReplacement(m.group(i)));
                }
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return line;
    }

    private void saveGeneratedJava(String className, String javaCode) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(className + ".java"))) {
            writer.write(javaCode);
        } catch (IOException e) {
            outputConsole.append("Error saving Java file: " + e.getMessage() + "\n");
        }
    }

    private void runGeneratedJava(String className) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            int result = compiler.run(null, null, null, className + ".java");
            if (result != 0) {
                outputConsole.append("Compilation failed!\n");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder("java", "-cp", ".;lib/*", className);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                outputConsole.append(line + "\n");
            }
            reader.close();
            process.waitFor();

        } catch (Exception e) {
            outputConsole.append("Error running Java: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    private void saveGeneratedJavaFromTab() {
        RSyntaxTextArea editor = getCurrentEditor();
        if (editor == null) return;

        String javaCode = convertPseudoToJava(editor.getText());
        javaCode = wrapMain(javaCode);

        JFileChooser chooser = new JFileChooser();
        int ret = chooser.showSaveDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(f))) {
                writer.write(javaCode);
                outputConsole.append("Java code saved to: " + f.getAbsolutePath() + "\n");
            } catch (IOException e) {
                outputConsole.append("Error saving Java code: " + e.getMessage() + "\n");
            }
        }
    }

    private void showGeneratedJava() {
        RSyntaxTextArea editor = getCurrentEditor();
        if (editor == null) return;
        String javaCode = convertPseudoToJava(editor.getText());
        javaCode = wrapMain(javaCode);
        JTextArea display = new JTextArea(javaCode, 30, 80);
        display.setEditable(false);
        JScrollPane sp = new JScrollPane(display);
        JOptionPane.showMessageDialog(this, sp, "Generated Java Code", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser(currentDirectory);
        int ret = chooser.showOpenDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                RSyntaxTextArea editor = getCurrentEditor();
                if (editor != null) editor.setText(content);
                tabbedPane.setTitleAt(tabbedPane.getSelectedIndex(), f.getName());
            } catch (IOException e) {
                outputConsole.append("Error opening file: " + e.getMessage() + "\n");
            }
        }
    }

    private void saveCurrentTab() {
        RSyntaxTextArea editor = getCurrentEditor();
        if (editor == null) return;

        String title = tabbedPane.getTitleAt(tabbedPane.getSelectedIndex());
        File file = new File(currentDirectory, title);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(editor.getText());
            outputConsole.append("File saved: " + file.getAbsolutePath() + "\n");
        } catch (IOException e) {
            outputConsole.append("Error saving file: " + e.getMessage() + "\n");
        }
    }

    private void saveCurrentTabAs() {
        RSyntaxTextArea editor = getCurrentEditor();
        if (editor == null) return;

        JFileChooser chooser = new JFileChooser(currentDirectory);
        int ret = chooser.showSaveDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(f))) {
                writer.write(editor.getText());
                tabbedPane.setTitleAt(tabbedPane.getSelectedIndex(), f.getName());
                outputConsole.append("File saved: " + f.getAbsolutePath() + "\n");
            } catch (IOException e) {
                outputConsole.append("Error saving file: " + e.getMessage() + "\n");
            }
        }
    }

    private void closeCurrentTab() {
        int index = tabbedPane.getSelectedIndex();
        if (index != -1) tabbedPane.removeTabAt(index);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(NovaSwingIDECompiler::new);
    }
}
