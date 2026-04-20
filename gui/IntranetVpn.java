import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class IntranetVpn {

    private static final String EXE_NAME = "zju-connect.exe";
    private static final String SERVER = "vpn.your-school.edu.cn";
    private static final String SOCKS_BIND = "127.0.0.1:1180";
    private static final String HTTP_BIND = "127.0.0.1:1181";
    private static final String EDGE_EXE =
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

    private JFrame frame;
    private JTextField userField;
    private JPasswordField passField;
    private JButton connectBtn;
    private JButton edgeBtn;
    private JLabel statusLabel;
    private JTextArea logArea;
    private Process process;
    private File exeFile;
    private File workDir;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new IntranetVpn().show());
    }

    private void show() {
        resolveExe();

        frame = new JFrame("内网 VPN");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                disconnect();
                frame.dispose();
                System.exit(0);
            }
        });

        JPanel main = new JPanel(new BorderLayout(10, 10));
        main.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0;
        form.add(new JLabel("学号:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        userField = new JTextField(18);
        form.add(userField, g);

        g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        form.add(new JLabel("密码:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        passField = new JPasswordField(18);
        passField.addActionListener(e -> toggleConnection());
        form.add(passField, g);

        main.add(form, BorderLayout.NORTH);

        logArea = new JTextArea(14, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(logArea);
        main.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 0));
        statusLabel = new JLabel(exeFile != null
                ? "状态: 未连接"
                : "状态: 找不到 " + EXE_NAME);
        south.add(statusLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        edgeBtn = new JButton("打开内网 Edge");
        edgeBtn.setEnabled(false);
        edgeBtn.addActionListener(e -> openEdge());
        connectBtn = new JButton("连接");
        connectBtn.setEnabled(exeFile != null);
        connectBtn.addActionListener(e -> toggleConnection());
        buttons.add(edgeBtn);
        buttons.add(connectBtn);
        south.add(buttons, BorderLayout.EAST);

        main.add(south, BorderLayout.SOUTH);

        frame.setContentPane(main);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        userField.requestFocusInWindow();
    }

    private void resolveExe() {
        File jarDir = getJarDir();
        File[] candidates = {
            new File(EXE_NAME),
            jarDir == null ? null : new File(jarDir, EXE_NAME),
            jarDir == null || jarDir.getParentFile() == null
                    ? null : new File(jarDir.getParentFile(), EXE_NAME),
        };
        for (File f : candidates) {
            if (f != null && f.isFile()) {
                exeFile = f;
                workDir = f.getParentFile();
                return;
            }
        }
    }

    private static File getJarDir() {
        try {
            File jar = new File(IntranetVpn.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return jar.getParentFile();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void toggleConnection() {
        if (connected.get() || (process != null && process.isAlive())) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        if (exeFile == null) {
            statusLabel.setText("状态: 找不到 " + EXE_NAME);
            return;
        }
        String user = userField.getText().trim();
        char[] passChars = passField.getPassword();
        String pass = new String(passChars);
        if (user.isEmpty() || pass.isEmpty()) {
            statusLabel.setText("状态: 请输入学号和密码");
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                exeFile.getAbsolutePath(),
                "-server", SERVER,
                "-username", user,
                "-password", pass,
                "-socks-bind", SOCKS_BIND,
                "-http-bind", HTTP_BIND,
                "-disable-zju-config",
                "-disable-zju-dns",
                "-skip-domain-resource",
                "-keep-alive-url", "http://" + SERVER + "/"
        );
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        try {
            logArea.setText("");
            process = pb.start();
            connectBtn.setText("断开");
            statusLabel.setText("状态: 正在连接...");

            java.util.Arrays.fill(passChars, '\0');
            passField.setText("");

            Thread reader = new Thread(this::pumpLog, "zju-connect-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException ex) {
            statusLabel.setText("状态: 启动失败 " + ex.getMessage());
        }
    }

    private void pumpLog() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                final String l = line;
                SwingUtilities.invokeLater(() -> {
                    logArea.append(l + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                    String low = l.toLowerCase();
                    if (low.contains("socks5 server listening")
                            || low.contains("http server listening")
                            || low.contains("zju connect is ready")) {
                        if (connected.compareAndSet(false, true)) {
                            statusLabel.setText("状态: 已连接");
                            edgeBtn.setEnabled(true);
                        }
                    } else if (!connected.get()
                            && (low.contains("login error")
                                || low.contains("authentication failed")
                                || low.contains("invalid username")
                                || low.contains("password"))) {
                        if (low.contains("error") || low.contains("failed") || low.contains("invalid")) {
                            statusLabel.setText("状态: 连接失败 (看日志)");
                        }
                    }
                });
            }
            int code = process.waitFor();
            SwingUtilities.invokeLater(() -> {
                connected.set(false);
                connectBtn.setText("连接");
                edgeBtn.setEnabled(false);
                statusLabel.setText("状态: 已断开 (退出码 " + code + ")");
            });
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() ->
                    logArea.append("[reader error] " + ex + "\n"));
        }
    }

    private void disconnect() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        connected.set(false);
        connectBtn.setText("连接");
        edgeBtn.setEnabled(false);
        statusLabel.setText("状态: 未连接");
    }

    private void openEdge() {
        try {
            File edge = new File(EDGE_EXE);
            if (!edge.isFile()) {
                logArea.append("[open edge error] msedge.exe not found at " + EDGE_EXE + "\n");
                return;
            }
            new ProcessBuilder(
                    EDGE_EXE,
                    "--proxy-server=socks5://" + SOCKS_BIND,
                    "--user-data-dir=" + System.getenv("LOCALAPPDATA") + "\\Edge-Intranet"
            ).start();
        } catch (IOException ex) {
            logArea.append("[open edge error] " + ex + "\n");
        }
    }
}
