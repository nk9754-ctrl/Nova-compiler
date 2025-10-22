
// Requires: gson, rsyntaxtextarea, autocomplete jars
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
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

public class NovaSwingIDECompiler extends JFrame {
    private JTabbedPane tabbedPane;
    private JTextArea outputArea;
    private JTree projectExplorer;
    private Process runningProcess;
    private BufferedWriter processWriter;
    private Map<Component, EditorTab> editorTabs = new HashMap<>();
    private List<Rule> rules;
    private String lastGeneratedJava = "";
    private String mainClassName = "Main";
    private List<String> generatedJavaFiles = new ArrayList<>();

    static class Rule {
        String keyword;
        List<String> patterns;
        String template;
        transient List<Pattern> compiledPatterns;

        void compilePatterns() {
            compiledPatterns = new ArrayList<>();
            for (String p : patterns) {
                compiledPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
            }
        }
    }

    static class EditorTab {
        RSyntaxTextArea codeArea;
        File file;
        String className = "Main";

        EditorTab(RSyntaxTextArea area, File file) {
            this.codeArea = area;
            this.file = file;
        }
    }

    public NovaSwingIDECompiler() {
        setTitle("🌌 Nova Compiler ");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);

        // Project Explorer
        projectExplorer = new JTree(createFileTree(new File(".")));
        projectExplorer.setRootVisible(true);
        projectExplorer.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) projectExplorer.getLastSelectedPathComponent();
            if (node == null || !(node.getUserObject() instanceof File))
                return;
            File file = (File) node.getUserObject();
            if (file.isFile() && file.getName().endsWith(".txt"))
                openFileInTab(file);
        });
        JScrollPane projectScroll = new JScrollPane(projectExplorer);
        projectScroll.setPreferredSize(new Dimension(220, 800));

        // Tabbed Code Area
        tabbedPane = new JTabbedPane();
        addNewTab(null);

        // Output Area (Interactive Console)
        outputArea = new JTextArea();
        outputArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        outputArea.setBackground(new Color(20, 20, 20));
        outputArea.setForeground(new Color(255, 200, 50));
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        JScrollPane outputScroll = new JScrollPane(outputArea);

        outputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && processWriter != null) {
                    try {
                        int lastLineStart = outputArea.getText().lastIndexOf('\n') + 1;
                        String command = outputArea.getText().substring(lastLineStart).trim();
                        processWriter.write(command + "\n");
                        processWriter.flush();
                    } catch (IOException ex) {
                        outputArea.append("\n[Error sending input: " + ex.getMessage() + "]");
                    }
                }
            }
        });

        // Split Panes
        JSplitPane horizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectScroll, tabbedPane);
        horizontalSplit.setDividerLocation(240);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, horizontalSplit, outputScroll);
        mainSplit.setDividerLocation(530);
        getContentPane().add(mainSplit);

        setJMenuBar(createMenuBar());

        try {
            loadRules("rules.json");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error loading rules.json: " + ex.getMessage());
            System.exit(1);
        }
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem newTab = new JMenuItem("New Tab");
        newTab.addActionListener(e -> addNewTab(null));
        fileMenu.add(newTab);

        JMenuItem open = new JMenuItem("Open...");
        open.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            int ret = chooser.showOpenDialog(this);
            if (ret == JFileChooser.APPROVE_OPTION)
                openFileInTab(chooser.getSelectedFile());
        });
        fileMenu.add(open);

        JMenuItem save = new JMenuItem("Save");
        save.addActionListener(e -> saveCurrentTab());
        fileMenu.add(save);

        JMenuItem saveAs = new JMenuItem("Save As...");
        saveAs.addActionListener(e -> saveCurrentTabAs());
        fileMenu.add(saveAs);

        JMenuItem closeTab = new JMenuItem("Close Tab");
        closeTab.addActionListener(e -> closeCurrentTab());
        fileMenu.add(closeTab);

        menuBar.add(fileMenu);

        JMenu runMenu = new JMenu("Run");
        JMenuItem compileRun = new JMenuItem("Compile & Run");
        compileRun.addActionListener(e -> compileAndRun());
        runMenu.add(compileRun);

        JMenuItem clearOutput = new JMenuItem("Clear Output");
        clearOutput.addActionListener(e -> outputArea.setText(""));
        runMenu.add(clearOutput);

        menuBar.add(runMenu);

        JMenu viewMenu = new JMenu("View");
        JMenuItem showJava = new JMenuItem("Show Generated Java Code");
        showJava.addActionListener(e -> showGeneratedJava());
        viewMenu.add(showJava);

        menuBar.add(viewMenu);

        return menuBar;
    }

    private void addNewTab(File file) {
        RSyntaxTextArea codeArea = new RSyntaxTextArea(28, 80);
        codeArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        codeArea.setCodeFoldingEnabled(true);
        codeArea.setTabsEmulated(true);
        codeArea.setTabSize(4);
        codeArea.setFont(new Font("Consolas", Font.PLAIN, 16));
        codeArea.setBackground(new Color(30, 30, 30));
        codeArea.setForeground(new Color(0, 255, 180));
        if (file != null && file.exists()) {
            try {
                codeArea.setText(new String(Files.readAllBytes(file.toPath())));
            } catch (IOException ignored) {
            }
        }
        RTextScrollPane scroll = new RTextScrollPane(codeArea);
        String title = (file != null) ? file.getName() : "Untitled " + (tabbedPane.getTabCount() + 1);
        tabbedPane.addTab(title, scroll);
        EditorTab edtab = new EditorTab(codeArea, file);
        editorTabs.put(scroll, edtab);
        tabbedPane.setSelectedComponent(scroll);
        addAutoCompletion(codeArea);
    }

    private void openFileInTab(File file) {
        for (Map.Entry<Component, EditorTab> entry : editorTabs.entrySet())
            if (file.equals(entry.getValue().file)) {
                tabbedPane.setSelectedComponent(entry.getKey());
                return;
            }
        addNewTab(file);
    }

    private void saveCurrentTab() {
        Component comp = tabbedPane.getSelectedComponent();
        EditorTab tab = editorTabs.get(comp);
        if (tab == null)
            return;
        if (tab.file == null) {
            saveCurrentTabAs();
        } else {
            try (FileWriter fw = new FileWriter(tab.file)) {
                fw.write(outputArea.getText());
                JOptionPane.showMessageDialog(this, "Saved: " + tab.file.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            }
        }
    }

    private void saveCurrentTabAs() {
        Component comp = tabbedPane.getSelectedComponent();
        EditorTab tab = editorTabs.get(comp);
        if (tab == null)
            return;
        JFileChooser chooser = new JFileChooser();
        int ret = chooser.showSaveDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(outputArea.getText());
                tab.file = file;
                tabbedPane.setTitleAt(tabbedPane.getSelectedIndex(), file.getName());
                JOptionPane.showMessageDialog(this, "Saved as: " + file.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            }
        }
    }

    private void closeCurrentTab() {
        int idx = tabbedPane.getSelectedIndex();
        if (idx >= 0) {
            Component comp = tabbedPane.getComponentAt(idx);
            tabbedPane.remove(idx);
            editorTabs.remove(comp);
        }
        if (tabbedPane.getTabCount() == 0)
            addNewTab(null);
    }

    private static DefaultMutableTreeNode createFileTree(File dir) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(dir);
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.isDirectory() &&
                        (!f.getName().equals("node_modules") && !f.getName().startsWith(".")))
                    node.add(createFileTree(f));
                else if (f.isFile() && (f.getName().endsWith(".txt") || f.getName().endsWith(".java")))
                    node.add(new DefaultMutableTreeNode(f));
            }
        }
        return node;
    }

    private RSyntaxTextArea createCodeArea() {
        RSyntaxTextArea area = new RSyntaxTextArea(28, 80);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        area.setCodeFoldingEnabled(true);
        area.setTabsEmulated(true);
        area.setTabSize(4);
        area.setFont(new Font("Consolas", Font.PLAIN, 16));
        area.setBackground(new Color(30, 30, 30));
        area.setForeground(new Color(0, 255, 180));
        return area;
    }

    private void addAutoCompletion(RSyntaxTextArea area) {
        CompletionProvider provider = new DefaultCompletionProvider();
        for (String kw : new String[] { "public", "private", "protected", "class", "static", "void", "int", "double",
                "boolean", "if", "else", "for", "while", "return", "new", "import", "String" }) {
            ((DefaultCompletionProvider) provider).addCompletion(new BasicCompletion(provider, kw));
        }
        new AutoCompletion(provider).install(area);
    }

    private void loadRules(String jsonFile) throws Exception {
        String jsonStr = new String(Files.readAllBytes(Paths.get(jsonFile)));
        Gson gson = new Gson();
        Rule[] arr = gson.fromJson(jsonStr, Rule[].class);
        rules = Arrays.asList(arr);
        for (Rule r : rules)
            r.compilePatterns();
    }

    private void compileAndRun() {
        Component comp = tabbedPane.getSelectedComponent();
        EditorTab tab = editorTabs.get(comp);
        if (tab == null)
            return;

        outputArea.setText("");

        List<String> lines = Arrays.asList(tab.codeArea.getText().split("\\r?\\n"));
        String javaCode = convertPseudoToJava(lines);
        String className = mainClassName; // mainClassName set during conversion

        try {
            // If multiple .java files were generated, compile all of them
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            ByteArrayOutputStream errStream = new ByteArrayOutputStream();

            List<String> filesToCompile = new ArrayList<>();

            // Use generatedJavaFiles from convertOOP() if available
            if (generatedJavaFiles != null && !generatedJavaFiles.isEmpty()) {
                filesToCompile.addAll(generatedJavaFiles);
            } else {
                // Fallback: write the single java file manually
                try (FileWriter fw = new FileWriter(className + ".java")) {
                    fw.write(javaCode);
                }
                filesToCompile.add(className + ".java");
            }

            // Compile all .java files together
            String[] fileArray = filesToCompile.toArray(new String[0]);
            int result = compiler.run(null, null, errStream, fileArray);

            if (result == 0) {
                // Run the main class
                runningProcess = Runtime.getRuntime().exec("java " + className);
                processWriter = new BufferedWriter(new OutputStreamWriter(runningProcess.getOutputStream()));

                new Thread(() -> readStream(runningProcess.getInputStream())).start();
                new Thread(() -> readStream(runningProcess.getErrorStream())).start();
            } else {
                outputArea.setText("Compilation failed:\n" + errStream.toString());
            }

        } catch (Exception ex) {
            outputArea.setText("Error: " + ex.getMessage());
        }
    }

    private void readStream(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String msg = line;
                SwingUtilities.invokeLater(() -> {
                    outputArea.append(msg + "\n");
                    outputArea.setCaretPosition(outputArea.getDocument().getLength());
                });
            }
        } catch (IOException ignored) {
        }
    }

    private void showGeneratedJava() {
        Component comp = tabbedPane.getSelectedComponent();
        EditorTab tab = editorTabs.get(comp);
        if (tab == null)
            return;
        List<String> lines = Arrays.asList(tab.codeArea.getText().split("\\r?\\n"));
        String javaCode = convertPseudoToJava(lines);
        JFrame frame = new JFrame("Generated Java Code");
        JTextArea area = new JTextArea(javaCode);
        area.setFont(new Font("Consolas", Font.PLAIN, 14));
        frame.add(new JScrollPane(area));
        frame.setSize(800, 600);
        frame.setVisible(true);
    }

    private boolean isValidJavaIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0)))
            return false;
        for (int i = 1; i < s.length(); i++)
            if (!Character.isJavaIdentifierPart(s.charAt(i)))
                return false;
        return true;
    }

    private String convertPseudoToJava(List<String> lines) {
        if (hasOOPStructure(lines))
            return convertOOP(lines);
        else
            return convertProcedural(lines);
    }

    private boolean hasOOPStructure(List<String> lines) {
        for (String line : lines) {
            String l = line.trim().toLowerCase();
            if (l.startsWith("class ") || l.startsWith("function "))
                return true;
        }
        return false;
    }

    private String convertProcedural(List<String> lines) {
        StringBuilder java = new StringBuilder();
        java.append("import java.util.*;\n");
        java.append("public class Main {\n");
        java.append("    public static void main(String[] args) {\n");
        java.append("        Scanner sc = new Scanner(System.in);\n");
        for (String line : lines)
            java.append("        ").append(convertLine(line.trim())).append("\n");
        java.append("    }\n}");
        mainClassName = "Main";
        lastGeneratedJava = java.toString();
        return java.toString();
    }

    // helper to hold one class contents
    static class ClassDef {
        String name;
        String parent; // nullable
        List<String> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        StringBuilder mainBody = new StringBuilder(); // for statements that go into this class's main, if any

        ClassDef(String name, String parent) {
            this.name = name;
            this.parent = parent;
        }

        String buildSource(boolean includeImports) {
            StringBuilder sb = new StringBuilder();
            if (includeImports) {
                sb.append("import java.util.*;\n\n");
            }

            // public for main class only when appropriate; safe to keep package-private
            // except one main class if needed
            sb.append("public class ").append(name);
            if (parent != null && !parent.isEmpty())
                sb.append(" extends ").append(parent);
            sb.append(" {\n\n");

            // fields
            for (String f : fields) {
                sb.append("    ").append(f).append("\n");
            }
            if (!fields.isEmpty())
                sb.append("\n");

            // methods
            for (String m : methods) {
                // methods already contain their braces (from parse)
                String[] lines = m.split("\n");
                for (String ln : lines)
                    sb.append("    ").append(ln).append("\n");
                sb.append("\n");
            }

            // main (if has content)
            if (mainBody.length() > 0) {
                sb.append("    public static void main(String[] args) {\n");
                sb.append("        Scanner sc = new Scanner(System.in);\n");
                // indent existing mainBody lines by 8 spaces
                String[] lines = mainBody.toString().split("\\r?\\n");
                for (String ln : lines) {
                    if (!ln.trim().isEmpty())
                        sb.append("        ").append(ln).append("\n");
                }
                sb.append("    }\n");
            }

            sb.append("}\n");
            return sb.toString();
        }
    }

    // Replace your convertOOP(...) with this function
    private String convertOOP(List<String> pseudoLines) {

        // Map classname -> ClassDef
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        ClassDef active = null;
        boolean inMethod = false;
        String currentMethodSignature = null;
        StringBuilder currentMethodBody = new StringBuilder();

        // regex for class line: "class X" or "class X, Y"
        Pattern classPattern = Pattern.compile("^class\\s+(\\w+)(?:\\s*,\\s*(\\w+))?$", Pattern.CASE_INSENSITIVE);

        for (String raw : pseudoLines) {
            String line = raw.trim();
            if (line.isEmpty())
                continue;

            Matcher clsM = classPattern.matcher(line);
            if (clsM.matches()) {
                // start new active class
                String cname = clsM.group(1);
                String parent = clsM.groupCount() >= 2 ? clsM.group(2) : null;
                if (parent != null && parent.trim().isEmpty())
                    parent = null;
                active = classes.get(cname);
                if (active == null) {
                    active = new ClassDef(cname, parent);
                    classes.put(cname, active);
                } else {
                    // update parent if previously unknown
                    if ((active.parent == null || active.parent.isEmpty()) && parent != null)
                        active.parent = parent;
                }
                // reset method state
                inMethod = false;
                currentMethodSignature = null;
                currentMethodBody.setLength(0);
                continue;
            }

            // If we haven't seen any class yet, create a default Main class
            if (active == null) {
                active = classes.get("Main");
                if (active == null) {
                    active = new ClassDef("Main", null);
                    classes.put("Main", active);
                }
            }

            // method start detection (explicit "function" or signature-like "name(...)" )
            if (line.toLowerCase().startsWith("function ")) {
                inMethod = true;
                currentMethodBody.setLength(0);
                currentMethodSignature = parseFunctionSignature(line.substring("function".length()).trim(),
                        active.name);
                continue;
            } else if (!inMethod) {
                // try method without function keyword
                String sigTry = parseFunctionSignature(line, active.name);
                if (!sigTry.contains("unknownMethod")) {
                    inMethod = true;
                    currentMethodSignature = sigTry;
                    currentMethodBody.setLength(0);
                    continue;
                }
            }

            // endfunction -> close method
            if (line.equalsIgnoreCase("endfunction") && inMethod) {
                // compose method text: signature + body + closing brace
                StringBuilder methodText = new StringBuilder();
                methodText.append(currentMethodSignature).append(" {\n");
                methodText.append(currentMethodBody.toString());
                methodText.append("    }\n"); // method closing
                active.methods.add(methodText.toString());
                inMethod = false;
                currentMethodBody.setLength(0);
                currentMethodSignature = null;
                continue;
            }

            if (inMethod) {
                // add to current method body (already will be indented when emitted)
                currentMethodBody.append("        ").append(convertLine(line)).append("\n");
                continue;
            }

            // field detection
            if (line.toLowerCase()
                    .matches("^(public|private|protected)?\\s*(int|double|string|boolean|\\w+)\\s+\\w+;?")) {
                String field = convertFieldDeclaration(line);
                if (!active.fields.contains(field))
                    active.fields.add(field);
                continue;
            }

            // else treat as class-level main body (statements that should go into main)
            active.mainBody.append(convertLine(line)).append("\n");
        }

        // If no Main class exists, create a simple empty main if required
        if (!classes.containsKey("Main")) {
            // nothing to do; user might not need a main
        }

        // Write each class to its own .java file and collect filenames for compilation
        List<String> generatedFiles = new ArrayList<>();
        for (Map.Entry<String, ClassDef> e : classes.entrySet()) {
            String cname = e.getKey();
            boolean includeImport = cname.equals(mainClassName);
            String src = e.getValue().buildSource(includeImport);
            try (FileWriter fw = new FileWriter(cname + ".java")) {
                fw.write(src);
                generatedFiles.add(cname + ".java");
            } catch (IOException ex) {
                // remember but continue
                outputArea.append("Error writing " + cname + ".java: " + ex.getMessage() + "\n");
            }
        }

        // store lastGeneratedJava as concatenation for display
        StringBuilder allClasses = new StringBuilder();
        for (ClassDef d : classes.values()) {
            boolean includeImport = d.name.equals(mainClassName);
            allClasses.append(d.buildSource(includeImport)).append("\n\n");
        }
        lastGeneratedJava = allClasses.toString();

        // set mainClassName if Main exists, else pick first class
        if (classes.containsKey("Main"))
            mainClassName = "Main";
        else if (!classes.isEmpty())
            mainClassName = classes.keySet().iterator().next();

        // store generated filenames somewhere accessible for compile step
        // (we'll set a field generatedJavaFiles for compileAndRun to use)
        this.generatedJavaFiles = generatedFiles;

        return lastGeneratedJava;
    }

    private String parseFunctionSignature(String line, String currentClassName) {
        String rest = line.trim();
        Matcher mRet = Pattern.compile("^(\\w+)\\s+(\\w+)\\s*\\((.*)\\)$").matcher(rest);
        if (mRet.matches()) {
            String returnType = mRet.group(1);
            if (returnType.equalsIgnoreCase("string"))
                returnType = "String";
            String methodName = mRet.group(2);
            String paramsRaw = mRet.group(3).trim();
            List<String> paramList = new ArrayList<>();
            if (!paramsRaw.isEmpty()) {
                for (String param : paramsRaw.split(",")) {
                    param = param.trim();
                    String[] p = param.split("\\s+");
                    if (p.length == 2) {
                        String type = p[0].equalsIgnoreCase("string") ? "String" : p[0];
                        paramList.add(type + " " + p[1]);
                    } else
                        paramList.add(param);
                }
            }
            if (methodName.equals(currentClassName)) {
                return "public " + currentClassName + "(" + String.join(", ", paramList) + ")";
            } else {
                return "public " + returnType + " " + methodName + "(" + String.join(", ", paramList) + ")";
            }
        }
        Matcher m = Pattern.compile("^(\\w+)\\s*\\((.*)\\)$").matcher(rest);
        if (m.matches()) {
            String methodName = m.group(1);
            String paramsRaw = m.group(2).trim();
            List<String> paramList = new ArrayList<>();
            if (!paramsRaw.isEmpty()) {
                for (String param : paramsRaw.split(",")) {
                    param = param.trim();
                    String[] p = param.split("\\s+");
                    if (p.length == 2) {
                        String type = p[0].equalsIgnoreCase("string") ? "String" : p[0];
                        paramList.add(type + " " + p[1]);
                    } else
                        paramList.add(param);
                }
            }
            if (methodName.equals(currentClassName)) {
                return "public " + currentClassName + "(" + String.join(", ", paramList) + ")";
            } else {
                return "public void " + methodName + "(" + String.join(", ", paramList) + ")";
            }
        }
        return "public void unknownMethod() { /* ERROR Parsing function signature: " + line + " */ }";
    }

    private String convertFieldDeclaration(String line) {
        String[] parts = line.split("\\s+");
        String modifier = "private";
        int idx = 0;
        if (parts.length > 0 && (parts[0].equalsIgnoreCase("public") ||
                parts[0].equalsIgnoreCase("private") || parts[0].equalsIgnoreCase("protected"))) {
            modifier = parts[0].toLowerCase();
            idx = 1;
        }
        if (parts.length - idx < 2)
            return "// ERROR: Invalid field declaration: " + line;
        String type = parts[idx];
        if (type.equalsIgnoreCase("string"))
            type = "String";
        String varName = parts[idx + 1];
        return modifier + " " + type + " " + varName + ";";
    }

    private String convertLine(String line) {
        for (Rule r : rules) {
            for (Pattern p : r.compiledPatterns) {
                Matcher m = p.matcher(line);
                if (m.matches()) {
                    if ("for loop".equalsIgnoreCase(r.keyword) || "for".equalsIgnoreCase(r.keyword)) {
                        return String.format(r.template,
                                m.group(1),
                                m.group(2),
                                m.group(1),
                                m.group(3),
                                m.group(1));
                    } else {
                        String javaLine = r.template;
                        for (int i = 1; i <= m.groupCount(); i++) {
                            javaLine = javaLine.replaceFirst("%s", Matcher.quoteReplacement(m.group(i)));
                        }
                        return javaLine;
                    }
                }
            }
        }
        if (line.toLowerCase().startsWith("input")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 3) {
                String type = parts[1].toLowerCase(), var = parts[2];
                if (type.equals("int") || type.equals("double") || type.equals("boolean")) {
                    return type + " " + var + " = sc.next" + type.substring(0, 1).toUpperCase() + type.substring(1)
                            + "();";
                } else if (type.equals("string")) {
                    return "String " + var + " = sc.nextLine();";
                } else
                    return "// ERROR: Unsupported input type: " + type;
            } else
                return "// ERROR: Invalid input statement: " + line;
        }
        if (line.toLowerCase().startsWith("print")) {
            String content = line.substring(5).trim();
            if (!content.startsWith("("))
                content = "(" + content + ")";
            return "System.out.println" + content + ";";
        }
        if (line.matches(".*[^;{}]$"))
            return line + ";";
        return line;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new NovaSwingIDECompiler().setVisible(true));
    }
}
