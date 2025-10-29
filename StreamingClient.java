import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.*;
import javax.swing.*;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class StreamingClient {
    private static final Logger logger = Logger.getLogger(StreamingClient.class.getName());
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8888;
    
    // Υποστηριζόμενα formats και πρωτόκολλα
    private static final String[] FORMATS = {".avi", ".mp4", ".mkv"};
    private static final String[] PROTOCOLS = {"TCP", "UDP", "RTP/UDP"};
    
    // Αυτόματη επιλογή πρωτοκόλλου βάσει ανάλυσης
    private static final Map<String, String> AUTO_PROTOCOL_SELECTION = Map.of(
        "240p", "TCP",
        "360p", "UDP",
        "480p", "UDP",
        "720p", "RTP/UDP",
        "1080p", "RTP/UDP"
    );
    
    private JFrame gui;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JLabel speedLabel;
    private JComboBox<String> formatComboBox;
    private JList<String> videoList;
    private DefaultListModel<String> videoListModel;
    private JComboBox<String> protocolComboBox;
    private JCheckBox autoProtocolCheckBox;
    private JButton connectButton;
    private JButton speedTestButton;
    private JButton getVideosButton;
    private JButton streamButton;
    
    private Socket serverSocket;
    private BufferedReader serverInput;
    private PrintWriter serverOutput;
    private double connectionSpeed = 0.0; // Mbps
    private boolean isConnected = false;
    private List<String> availableVideos;
    
    public StreamingClient() {
        setupLogger();
        availableVideos = new ArrayList<>();
        createGUI();
    }
    
    private void setupLogger() {
        try {
            FileHandler fileHandler = new FileHandler("streaming_client.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
            logger.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("Σφάλμα στη δημιουργία log file: " + e.getMessage());
        }
    }
    
    private void createGUI() {
        gui = new JFrame("Streaming Client");
        gui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        gui.setLayout(new BorderLayout());
        
        // Status panel
        JPanel statusPanel = createStatusPanel();
        
        // Connection panel
        JPanel connectionPanel = createConnectionPanel();
        
        // Speed test panel
        JPanel speedPanel = createSpeedTestPanel();
        
        // Video selection panel
        JPanel videoPanel = createVideoSelectionPanel();
        
        // Protocol selection panel
        JPanel protocolPanel = createProtocolSelectionPanel();
        
        // Control panel
        JPanel controlPanel = createControlPanel();
        
        // Log area
        logArea = new JTextArea(15, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createTitledBorder("Logs"));
        
        // Main content panel
        JPanel contentPanel = new JPanel(new GridLayout(0, 1, 5, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(statusPanel);
        contentPanel.add(connectionPanel);
        contentPanel.add(speedPanel);
        contentPanel.add(videoPanel);
        contentPanel.add(protocolPanel);
        contentPanel.add(controlPanel);
        
        gui.add(contentPanel, BorderLayout.CENTER);
        gui.add(logScrollPane, BorderLayout.SOUTH);
        
        gui.pack();
        gui.setLocationRelativeTo(null);
        gui.setVisible(true);
        
        // Custom log handler για GUI
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                SwingUtilities.invokeLater(() -> {
                    logArea.append(new SimpleFormatter().format(record));
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
            }
            
            @Override
            public void flush() {}
            
            @Override
            public void close() throws SecurityException {}
        });
        
        updateUIState();
    }
    
    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Κατάσταση Σύνδεσης"));
        
        statusLabel = new JLabel("Αποσυνδεδεμένος");
        statusLabel.setForeground(Color.RED);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        
        panel.add(new JLabel("Κατάσταση: "));
        panel.add(statusLabel);
        
        return panel;
    }
    
    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Σύνδεση με Server"));
        
        connectButton = new JButton("Σύνδεση");
        connectButton.addActionListener(e -> toggleConnection());
        
        panel.add(new JLabel("Server: " + SERVER_HOST + ":" + SERVER_PORT));
        panel.add(connectButton);
        
        return panel;
    }
    
    private JPanel createSpeedTestPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Έλεγχος Ταχύτητας"));
        
        speedTestButton = new JButton("Έλεγχος Ταχύτητας");
        speedTestButton.addActionListener(e -> performSpeedTest());
        
        speedLabel = new JLabel("Δεν έχει γίνει έλεγχος");
        speedLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        
        panel.add(speedTestButton);
        panel.add(new JLabel(" | Ταχύτητα: "));
        panel.add(speedLabel);
        
        return panel;
    }
    
    private JPanel createVideoSelectionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Επιλογή Βίντεο"));
        
        // Format selection
        JPanel formatPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        formatPanel.add(new JLabel("Format: "));
        formatComboBox = new JComboBox<>(FORMATS);
        formatPanel.add(formatComboBox);
        
        getVideosButton = new JButton("Λήψη Λίστας Βίντεο");
        getVideosButton.addActionListener(e -> getAvailableVideos());
        formatPanel.add(getVideosButton);
        
        // Video list
        videoListModel = new DefaultListModel<>();
        videoList = new JList<>(videoListModel);
        videoList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Προσθήκη listener για αλλαγή επιλογής
        videoList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateUIState();
            }
        });
        
        JScrollPane videoScrollPane = new JScrollPane(videoList);
        videoScrollPane.setPreferredSize(new Dimension(400, 150));
        
        panel.add(formatPanel, BorderLayout.NORTH);
        panel.add(videoScrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createProtocolSelectionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Επιλογή Πρωτοκόλλου"));
        
        autoProtocolCheckBox = new JCheckBox("Αυτόματη επιλογή", true);
        autoProtocolCheckBox.addActionListener(e -> {
            protocolComboBox.setEnabled(!autoProtocolCheckBox.isSelected());
        });
        
        protocolComboBox = new JComboBox<>(PROTOCOLS);
        protocolComboBox.setEnabled(false);
        
        panel.add(autoProtocolCheckBox);
        panel.add(new JLabel("Πρωτόκολλο: "));
        panel.add(protocolComboBox);
        
        return panel;
    }
    
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.setBorder(BorderFactory.createTitledBorder("Έλεγχος Streaming"));
        
        streamButton = new JButton("Έναρξη Streaming");
        streamButton.addActionListener(e -> startStreaming());
        
        JButton clearLogsButton = new JButton("Καθαρισμός Logs");
        clearLogsButton.addActionListener(e -> logArea.setText(""));
        
        panel.add(streamButton);
        panel.add(clearLogsButton);
        
        return panel;
    }
    
    private void updateUIState() {
        boolean hasSpeed = connectionSpeed > 0;
        boolean hasVideos = !availableVideos.isEmpty();
        boolean hasSelection = videoList.getSelectedValue() != null;
        
        getVideosButton.setEnabled(isConnected && hasSpeed);
        streamButton.setEnabled(isConnected && hasVideos && hasSelection);
        
        connectButton.setText(isConnected ? "Αποσύνδεση" : "Σύνδεση");
        statusLabel.setText(isConnected ? "Συνδεδεμένος" : "Αποσυνδεδεμένος");
        statusLabel.setForeground(isConnected ? Color.GREEN : Color.RED);
        
        // Debug logging
        logger.info("UpdateUIState - Connected: " + isConnected + 
                   ", Speed: " + connectionSpeed + 
                   ", Videos: " + availableVideos.size() + 
                   ", Selection: " + (hasSelection ? videoList.getSelectedValue() : "none"));
    }
    
    private void toggleConnection() {
        if (isConnected) {
            disconnect();
        } else {
            connect();
        }
    }
    
    private void connect() {
        try {
            serverSocket = new Socket(SERVER_HOST, SERVER_PORT);
            serverInput = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
            serverOutput = new PrintWriter(serverSocket.getOutputStream(), true);
            
            isConnected = true;
            logger.info("Συνδέθηκε επιτυχώς στον server " + SERVER_HOST + ":" + SERVER_PORT);
            
        } catch (IOException e) {
            logger.severe("Σφάλμα σύνδεσης με server: " + e.getMessage());
            JOptionPane.showMessageDialog(gui, 
                "Δεν ήταν δυνατή η σύνδεση με τον server.\nΒεβαιωθείτε ότι ο server είναι σε λειτουργία.",
                "Σφάλμα Σύνδεσης", 
                JOptionPane.ERROR_MESSAGE);
        }
        
        updateUIState();
    }
    
    private void disconnect() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            isConnected = false;
            
            // Καθαρισμός δεδομένων
            availableVideos.clear();
            videoListModel.clear();
            
            logger.info("Αποσυνδέθηκε από τον server");
            
        } catch (IOException e) {
            logger.warning("Σφάλμα κατά την αποσύνδεση: " + e.getMessage());
        }
        
        updateUIState();
    }
    
    private void performSpeedTest() {
        speedTestButton.setEnabled(false);
        speedLabel.setText("Γίνεται έλεγχος...");
        speedLabel.setForeground(Color.BLUE);
        
        // Εκτέλεση speed test σε ξεχωριστό thread
        new Thread(() -> {
            try {
                logger.info("Ξεκίνησε έλεγχος ταχύτητας σύνδεσης...");
                
                // Προσπάθεια χρήσης JSpeedTest αν είναι διαθέσιμο
                if (isJSpeedTestAvailable()) {
                    performRealSpeedTest();
                } else {
                    // Fallback σε προσομοίωση
                    logger.info("JSpeedTest δεν είναι διαθέσιμο, χρήση προσομοίωσης");
                    performSimulatedSpeedTest();
                }
                
            } catch (Exception e) {
                logger.severe("Σφάλμα κατά τον έλεγχο ταχύτητας: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    speedLabel.setText("Σφάλμα ελέγχου");
                    speedLabel.setForeground(Color.RED);
                    speedTestButton.setEnabled(true);
                });
            }
        }).start();
    }
    
    private boolean isJSpeedTestAvailable() {
        try {
            Class.forName("fr.bmartel.speedtest.SpeedTestSocket");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    private void performRealSpeedTest() {
        try {
            // Χρήση reflection για JSpeedTest (αν είναι διαθέσιμο)
            Class<?> speedTestClass = Class.forName("fr.bmartel.speedtest.SpeedTestSocket");
            Object speedTestSocket = speedTestClass.getDeclaredConstructor().newInstance();
            
            // Προσθήκη listener
            Class<?> listenerClass = Class.forName("fr.bmartel.speedtest.inter.ISpeedTestListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class[]{listenerClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onCompletion":
                            Object report = args[0];
                            double speedMbps = getSpeedFromReport(report) / 1_000_000.0; // Convert to Mbps
                            connectionSpeed = speedMbps;
                            
                            SwingUtilities.invokeLater(() -> {
                                speedLabel.setText(String.format("%.2f Mbps", connectionSpeed));
                                speedLabel.setForeground(Color.GREEN);
                                speedTestButton.setEnabled(true);
                                updateUIState();
                            });
                            break;
                            
                        case "onError":
                            SwingUtilities.invokeLater(() -> {
                                speedLabel.setText("Σφάλμα JSpeedTest");
                                speedLabel.setForeground(Color.RED);
                                speedTestButton.setEnabled(true);
                            });
                            break;
                            
                        case "onProgress":
                            float percent = (Float) args[0];
                            SwingUtilities.invokeLater(() -> {
                                speedLabel.setText(String.format("%.0f%%", percent));
                            });
                            break;
                    }
                    return null;
                }
            );
            
            // Προσθήκη listener και εκκίνηση test
            speedTestClass.getMethod("addSpeedTestListener", listenerClass).invoke(speedTestSocket, listener);
            speedTestClass.getMethod("startDownload", String.class, int.class)
                          .invoke(speedTestSocket, "http://ipv4.ikoula.testdebit.info/1M.iso", 5000);
            
            logger.info("Ξεκίνησε πραγματικός έλεγχος ταχύτητας με JSpeedTest");
            
        } catch (Exception e) {
            logger.warning("Σφάλμα JSpeedTest, χρήση προσομοίωσης: " + e.getMessage());
            performSimulatedSpeedTest();
        }
    }
    
    private double getSpeedFromReport(Object report) {
        try {
            Object transferRate = report.getClass().getMethod("getTransferRateBit").invoke(report);
            return ((Number) transferRate.getClass().getMethod("doubleValue").invoke(transferRate)).doubleValue();
        } catch (Exception e) {
            logger.warning("Σφάλμα ανάγνωσης ταχύτητας από report: " + e.getMessage());
            return 2000000.0; // 2 Mbps default
        }
    }
    
    private void performSimulatedSpeedTest() {
        try {
            // Προσομοίωση με πραγματικό HTTP download
            URL url = new URL("http://ipv4.download.thinkbroadband.com/1MB.zip");
            long startTime = System.currentTimeMillis();
            
            SwingUtilities.invokeLater(() -> {
                speedLabel.setText("Κατέβασμα δείγματος...");
            });
            
            try (InputStream in = url.openStream()) {
                byte[] buffer = new byte[8192];
                long totalBytes = 0;
                int bytesRead;
                
                while ((bytesRead = in.read(buffer)) != -1 && 
                       (System.currentTimeMillis() - startTime) < 5000) { // 5 δευτερόλεπτα max
                    totalBytes += bytesRead;
                    
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed > 1000) { // Update κάθε δευτερόλεπτο
                        double speedBps = (totalBytes * 1000.0) / elapsed;
                        double speedMbps = (speedBps * 8.0) / 1_000_000.0;
                        
                        SwingUtilities.invokeLater(() -> {
                            speedLabel.setText(String.format("%.2f Mbps", speedMbps));
                        });
                    }
                }
                
                long totalTime = System.currentTimeMillis() - startTime;
                if (totalTime > 0) {
                    double speedBps = (totalBytes * 1000.0) / totalTime;
                    connectionSpeed = (speedBps * 8.0) / 1_000_000.0; // Convert to Mbps
                } else {
                    connectionSpeed = 2.0; // Default fallback
                }
                
            }
            
            SwingUtilities.invokeLater(() -> {
                speedLabel.setText(String.format("%.2f Mbps", connectionSpeed));
                speedLabel.setForeground(Color.GREEN);
                speedTestButton.setEnabled(true);
                updateUIState();
            });
            
            logger.info(String.format("Ολοκληρώθηκε προσομοίωση speed test: %.2f Mbps", connectionSpeed));
            
        } catch (Exception e) {
            // Ultimate fallback
            connectionSpeed = 1.0 + Math.random() * 9.0;
            
            SwingUtilities.invokeLater(() -> {
                speedLabel.setText(String.format("%.2f Mbps (εκτίμηση)", connectionSpeed));
                speedLabel.setForeground(Color.ORANGE);
                speedTestButton.setEnabled(true);
                updateUIState();
            });
            
            logger.warning("Χρήση τυχαίας ταχύτητας λόγω σφάλματος: " + e.getMessage());
        }
    }
    
    private void getAvailableVideos() {
        if (!isConnected || connectionSpeed <= 0) {
            JOptionPane.showMessageDialog(gui, 
                "Πρέπει να συνδεθείτε και να κάνετε έλεγχο ταχύτητας πρώτα.",
                "Σφάλμα", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String selectedFormat = (String) formatComboBox.getSelectedItem();
        
        try {
            // Αποστολή αιτήματος στον server
            String request = "GET_VIDEOS:" + connectionSpeed + ":" + selectedFormat;
            serverOutput.println(request);
            logger.info("Στάλθηκε αίτημα: " + request);
            
            // Λήψη απάντησης
            String response = serverInput.readLine();
            if (response != null) {
                parseVideoListResponse(response);
            }
            
        } catch (IOException e) {
            logger.severe("Σφάλμα επικοινωνίας με server: " + e.getMessage());
            JOptionPane.showMessageDialog(gui, 
                "Σφάλμα επικοινωνίας με server: " + e.getMessage(),
                "Σφάλμα", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void parseVideoListResponse(String response) {
        logger.info("Λήφθηκε απάντηση: " + response);
        
        if (response.startsWith("ERROR:")) {
            String errorMessage = response.substring(6);
            logger.warning("Σφάλμα από server: " + errorMessage);
            JOptionPane.showMessageDialog(gui, 
                "Σφάλμα από server: " + errorMessage,
                "Σφάλμα", 
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        if (response.startsWith("VIDEO_LIST:")) {
            String videoData = response.substring(11);
            
            availableVideos.clear();
            videoListModel.clear();
            
            if (!videoData.isEmpty()) {
                String[] videos = videoData.split(";");
                for (String video : videos) {
                    if (!video.trim().isEmpty()) {
                        availableVideos.add(video.trim());
                        videoListModel.addElement(video.trim());
                    }
                }
            }
            
            logger.info("Λήφθηκαν " + availableVideos.size() + " διαθέσιμα βίντεο");
            
            if (availableVideos.isEmpty()) {
                JOptionPane.showMessageDialog(gui, 
                    "Δεν βρέθηκαν κατάλληλα βίντεο για την ταχύτητα σύνδεσής σας.",
                    "Πληροφορία", 
                    JOptionPane.INFORMATION_MESSAGE);
            }
        }
        
        updateUIState();
    }
    
    private void startStreaming() {
        String selectedVideo = videoList.getSelectedValue();
        if (selectedVideo == null) {
            JOptionPane.showMessageDialog(gui, 
                "Παρακαλώ επιλέξτε ένα βίντεο από τη λίστα.",
                "Σφάλμα", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // Επιλογή πρωτοκόλλου
        String protocol;
        if (autoProtocolCheckBox.isSelected()) {
            protocol = getAutoSelectedProtocol(selectedVideo);
            logger.info("Αυτόματη επιλογή πρωτοκόλλου: " + protocol + " για " + selectedVideo);
        } else {
            protocol = (String) protocolComboBox.getSelectedItem();
            logger.info("Χειροκίνητη επιλογή πρωτοκόλλου: " + protocol);
        }
        
        try {
            // Αποστολή αιτήματος streaming
            String request = "START_STREAM:" + selectedVideo + ":" + protocol;
            serverOutput.println(request);
            logger.info("Στάλθηκε αίτημα streaming: " + request);
            
            // Λήψη απάντησης
            String response = serverInput.readLine();
            if (response != null) {
                handleStreamingResponse(response, selectedVideo, protocol);
            }
            
        } catch (IOException e) {
            logger.severe("Σφάλμα κατά την έναρξη streaming: " + e.getMessage());
            JOptionPane.showMessageDialog(gui, 
                "Σφάλμα κατά την έναρξη streaming: " + e.getMessage(),
                "Σφάλμα", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private String getAutoSelectedProtocol(String videoFileName) {
        // Εξαγωγή ανάλυσης από το όνομα αρχείου
        String resolution = "480p"; // Default
        
        if (videoFileName.contains("-240p")) resolution = "240p";
        else if (videoFileName.contains("-360p")) resolution = "360p";
        else if (videoFileName.contains("-480p")) resolution = "480p";
        else if (videoFileName.contains("-720p")) resolution = "720p";
        else if (videoFileName.contains("-1080p")) resolution = "1080p";
        
        return AUTO_PROTOCOL_SELECTION.getOrDefault(resolution, "UDP");
    }
    
    private void handleStreamingResponse(String response, String videoFileName, String protocol) {
        logger.info("Απάντηση streaming: " + response);
        
        if (response.startsWith("ERROR:")) {
            String errorMessage = response.substring(6);
            logger.warning("Σφάλμα streaming από server: " + errorMessage);
            JOptionPane.showMessageDialog(gui, 
                "Σφάλμα streaming: " + errorMessage,
                "Σφάλμα", 
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        if (response.startsWith("STREAM_STARTED:")) {
            logger.info("Το streaming ξεκίνησε επιτυχώς");
            
            // Εκκίνηση client για λήψη stream
            startVideoClient(protocol);
            
            JOptionPane.showMessageDialog(gui, 
                "Το streaming ξεκίνησε!\nΒίντεο: " + videoFileName + "\nΠρωτόκολλο: " + protocol,
                "Streaming Ξεκίνησε", 
                JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void startVideoClient(String protocol) {
        new Thread(() -> {
            try {
                // Μεγαλύτερη καθυστέρηση για να ξεκινήσει σωστά ο server
                logger.info("Αναμονή για εκκίνηση streaming server...");
                Thread.sleep(4000); // Μειωμένη καθυστέρηση
                
                List<String> command = new ArrayList<>();
                
                switch (protocol.toUpperCase()) {
                    case "TCP":
                        command.addAll(Arrays.asList(
                            "ffplay", "-i", "tcp://localhost:9999",
                            "-window_title", "Streaming Client - TCP",
                            "-autoexit", "-loglevel", "warning",
                            "-fflags", "nobuffer"
                        ));
                        break;
                        
                    case "UDP":
                        command.addAll(Arrays.asList(
                            "ffplay", "-i", "udp://localhost:9999?fifo_size=100000&overrun_nonfatal=1",
                            "-window_title", "Streaming Client - UDP",
                            "-autoexit", "-loglevel", "warning",
                            "-fflags", "nobuffer+fastseek", 
                            "-flags", "low_delay",
                            "-framedrop", // Drop frames αν χρειάζεται
                            "-sync", "audio" // Sync με audio
                        ));
                        break;
                        
                    case "RTP/UDP":
                        // Για RTP χρησιμοποιούμε SDP file αν υπάρχει
                        String rtpSource = Files.exists(Paths.get("stream.sdp")) ? 
                                         "stream.sdp" : "rtp://localhost:9999";
                        command.addAll(Arrays.asList(
                            "ffplay", "-i", rtpSource,
                            "-window_title", "Streaming Client - RTP/UDP",
                            "-autoexit", "-loglevel", "warning",
                            "-fflags", "nobuffer", "-flags", "low_delay"
                        ));
                        break;
                        
                    default:
                        logger.warning("Μη υποστηριζόμενο πρωτόκολλο για client: " + protocol);
                        return;
                }
                
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                
                logger.info("Εκκίνηση FFPLAY client με εντολή: " + String.join(" ", command));
                
                Process process = pb.start();
                
                // Διάβασμα output από FFPLAY με καλύτερη διαχείριση
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                int consecutiveNans = 0;
                boolean streamDetected = false;
                boolean decodingStarted = false;
                long startTime = System.currentTimeMillis();
                
                while ((line = reader.readLine()) != null) {
                    logger.info("FFPLAY: " + line);
                    
                    // Ανίχνευση stream info
                    if (line.contains("Stream #") || line.contains("Video:") || line.contains("Audio:")) {
                        logger.info("Ανιχνεύθηκε stream info!");
                        streamDetected = true;
                        consecutiveNans = 0;
                    }
                    
                    // Ανίχνευση ότι ξεκίνησε το decoding
                    if (line.contains("frame=") && !line.contains("nan")) {
                        logger.info("Ξεκίνησε το video decoding!");
                        decodingStarted = true;
                        consecutiveNans = 0;
                    }
                    
                    // Ανίχνευση fps > 0
                    if (line.matches(".*fps=\\s*[0-9]+.*") && !line.contains("fps= 0")) {
                        logger.info("Επιτυχής αναπαραγωγή με fps!");
                        decodingStarted = true;
                        consecutiveNans = 0;
                    }
                    
                    // Μετρητής για consecutive nan values
                    if (line.contains("nan") && !decodingStarted) {
                        consecutiveNans++;
                    } else if (!line.contains("nan")) {
                        consecutiveNans = 0;
                    }
                    
                    // Timeout μόνο αν δεν έχει ξεκινήσει decoding
                    if (!decodingStarted) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        
                        // Περισσότερα consecutive nans ή timeout
                        if (consecutiveNans > 15 || elapsed > 20000) {
                            logger.warning("Timeout ή πολλά nan values - elapsed: " + elapsed/1000 + "s, nans: " + consecutiveNans);
                            process.destroyForcibly();
                            break;
                        }
                    }
                    
                    // Έλεγχος για σφάλματα σύνδεσης
                    if (line.contains("Connection refused") || line.contains("Address already in use")) {
                        logger.warning("Σφάλμα σύνδεσης: " + line);
                        break;
                    }
                }
                
                int exitCode = process.waitFor();
                logger.info("FFPLAY client τερμάτισε με κωδικό: " + exitCode);
                
                if (!decodingStarted && !streamDetected) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(gui, 
                            "Το streaming δεν ξεκίνησε επιτυχώς.\n" +
                            "Δοκιμάστε διαφορετικό πρωτόκολλο (TCP συνήθως λειτουργεί καλύτερα).",
                            "Σφάλμα Streaming", 
                            JOptionPane.WARNING_MESSAGE);
                    });
                }
                
            } catch (Exception e) {
                logger.severe("Σφάλμα εκκίνησης FFPLAY client: " + e.getMessage());
            }
        }).start();
    }
    
    public static void main(String[] args) {
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | 
                 IllegalAccessException | UnsupportedLookAndFeelException e) {
            // Fallback to default look and feel
            System.err.println("Could not set system look and feel: " + e.getMessage());
        }
        
        SwingUtilities.invokeLater(() -> {
            new StreamingClient();
        });
    }
}