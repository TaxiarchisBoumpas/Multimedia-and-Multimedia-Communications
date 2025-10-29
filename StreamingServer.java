import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class StreamingServer {
    private static final Logger logger = Logger.getLogger(StreamingServer.class.getName());
    private static final int SERVER_PORT = 8888;
    private static final String VIDEOS_FOLDER = "videos";
    
    // Υποστηριζόμενα formats και αναλύσεις
    private static final String[] FORMATS = {".avi", ".mp4", ".mkv"};
    private static final String[] RESOLUTIONS = {"240p", "360p", "480p", "720p", "1080p"};
    
    // Bitrate requirements σε Kbps
    private static final Map<String, Integer> MIN_BITRATES = Map.of(
        "240p", 300,
        "360p", 400,
        "480p", 500,
        "720p", 1500,
        "1080p", 3000
    );
    
    private Socket currentClientSocket;
    private BufferedReader currentClientInput;
    private PrintWriter currentClientOutput;
    private boolean clientConnected = false;
    
    public StreamingServer() {
        setupLogger();
        availableVideos = new HashMap<>();
        createGUI();
    }
    
    private void setupLogger() {
        try {
            FileHandler fileHandler = new FileHandler("streaming_server.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
            logger.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("Σφάλμα στη δημιουργία log file: " + e.getMessage());
        }
    }
    
    private void createGUI() {
        gui = new JFrame("Streaming Server");
        gui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        gui.setLayout(new BorderLayout());
        
        // Status panel
        JPanel statusPanel = new JPanel(new FlowLayout());
        statusLabel = new JLabel("Σταματημένος");
        statusLabel.setForeground(Color.RED);
        statusPanel.add(new JLabel("Κατάσταση: "));
        statusPanel.add(statusLabel);
        
        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout());
        JButton startButton = new JButton("Εκκίνηση Server");
        JButton stopButton = new JButton("Διακοπή Server");
        JButton refreshButton = new JButton("Ανανέωση Βίντεο");
        
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        refreshButton.addActionListener(e -> processVideos());
        
        JButton disconnectClientButton = new JButton("Αποσύνδεση Client");
        disconnectClientButton.addActionListener(e -> {
            if (clientConnected) {
                disconnectCurrentClient();
                logger.info("Χειροκίνητη αποσύνδεση client");
            } else {
                JOptionPane.showMessageDialog(gui, 
                    "Δεν υπάρχει συνδεδεμένος client.", 
                    "Πληροφορία", 
                    JOptionPane.INFORMATION_MESSAGE);
            }
        });
        
        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(refreshButton);
        controlPanel.add(disconnectClientButton);
        
        // Log area
        logArea = new JTextArea(20, 60);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Logs"));
        
        gui.add(statusPanel, BorderLayout.NORTH);
        gui.add(controlPanel, BorderLayout.CENTER);
        gui.add(scrollPane, BorderLayout.SOUTH);
        
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
    }
    
    public void startServer() {
        if (isRunning) {
            logger.warning("Ο server είναι ήδη σε λειτουργία");
            return;
        }
        
        try {
            // Δημιουργία φακέλου videos αν δεν υπάρχει
            Files.createDirectories(Paths.get(VIDEOS_FOLDER));
            
            // Επεξεργασία υπαρχόντων βίντεο
            processVideos();
            
            // Εκκίνηση server
            serverSocket = new ServerSocket(SERVER_PORT);
            isRunning = true;
            
            statusLabel.setText("Σε λειτουργία - Αναμονή client");
            statusLabel.setForeground(Color.GREEN);
            
            logger.info("Streaming Server ξεκίνησε στο port " + SERVER_PORT);
            logger.info("Διαθέσιμα βίντεο: " + getTotalVideoCount());
            
            // Thread για χειρισμό clients
            new Thread(this::handleClients).start();
            
        } catch (IOException e) {
            logger.severe("Σφάλμα εκκίνησης server: " + e.getMessage());
            isRunning = false;
            statusLabel.setText("Σφάλμα εκκίνησης");
            statusLabel.setForeground(Color.RED);
        }
    }
    
    public void stopServer() {
        if (!isRunning) {
            return;
        }
        
        try {
            isRunning = false;
            
            // Αποσύνδεση τρέχοντος client
            if (clientConnected) {
                disconnectCurrentClient();
            }
            
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            statusLabel.setText("Σταματημένος");
            statusLabel.setForeground(Color.RED);
            logger.info("Streaming Server σταμάτησε");
            
        } catch (IOException e) {
            logger.severe("Σφάλμα κατά τη διακοπή του server: " + e.getMessage());
        }
    }
    
    private void handleClients() {
        while (isRunning) {
            try {
                Socket newClientSocket = serverSocket.accept();
                String clientAddress = newClientSocket.getInetAddress().toString();
                
                // Αποσύνδεση προηγούμενου client αν υπάρχει
                if (clientConnected && currentClientSocket != null) {
                    logger.warning("Νέος client προσπαθεί να συνδεθεί. Αποσύνδεση προηγούμενου client...");
                    disconnectCurrentClient();
                }
                
                // Σύνδεση νέου client
                currentClientSocket = newClientSocket;
                currentClientInput = new BufferedReader(new InputStreamReader(currentClientSocket.getInputStream()));
                currentClientOutput = new PrintWriter(currentClientSocket.getOutputStream(), true);
                clientConnected = true;
                
                logger.info("Νέα σύνδεση από: " + clientAddress);
                updateClientStatus();
                
                // Χειρισμός του client σε ξεχωριστό thread
                new Thread(() -> handleSingleClient()).start();
                
            } catch (IOException e) {
                if (isRunning) {
                    logger.severe("Σφάλμα αποδοχής client: " + e.getMessage());
                }
            }
        }
    }
    
    private void handleSingleClient() {
        try {
            String request;
            while (clientConnected && (request = currentClientInput.readLine()) != null) {
                logger.info("Λήφθηκε αίτημα: " + request);
                
                String[] parts = request.split(":");
                String command = parts[0];
                
                switch (command) {
                    case "GET_VIDEOS":
                        handleGetVideos(parts, currentClientOutput);
                        break;
                    case "START_STREAM":
                        handleStartStream(parts, currentClientOutput);
                        break;
                    default:
                        currentClientOutput.println("ERROR:Άγνωστη εντολή");
                }
            }
            
        } catch (IOException e) {
            if (clientConnected) {
                logger.warning("Σφάλμα επικοινωνίας με client: " + e.getMessage());
            }
        } finally {
            disconnectCurrentClient();
        }
    }
    
    private void disconnectCurrentClient() {
        if (currentClientSocket != null) {
            try {
                currentClientSocket.close();
                logger.info("Αποσυνδέθηκε client");
            } catch (IOException e) {
                logger.warning("Σφάλμα κλεισίματος client socket: " + e.getMessage());
            }
        }
        
        currentClientSocket = null;
        currentClientInput = null;
        currentClientOutput = null;
        clientConnected = false;
        updateClientStatus();
    }
    
    private void updateClientStatus() {
        SwingUtilities.invokeLater(() -> {
            if (clientConnected && currentClientSocket != null) {
                statusLabel.setText("Συνδεδεμένος client: " + currentClientSocket.getInetAddress());
                statusLabel.setForeground(Color.BLUE);
            } else if (isRunning) {
                statusLabel.setText("Σε λειτουργία - Αναμονή client");
                statusLabel.setForeground(Color.GREEN);
            } else {
                statusLabel.setText("Σταματημένος");
                statusLabel.setForeground(Color.RED);
            }
        });
    }
    
    private void handleGetVideos(String[] parts, PrintWriter out) {
        if (parts.length < 3) {
            out.println("ERROR:Λανθασμένη μορφή αιτήματος");
            return;
        }
        
        try {
            double connectionSpeed = Double.parseDouble(parts[1]); // Mbps
            String format = parts[2]; // π.χ. ".mkv"
            
            List<VideoFile> suitableVideos = getSuitableVideos(connectionSpeed, format);
            
            StringBuilder response = new StringBuilder("VIDEO_LIST:");
            for (VideoFile video : suitableVideos) {
                response.append(video.toString()).append(";");
            }
            
            out.println(response.toString());
            logger.info("Στάλθηκαν " + suitableVideos.size() + " κατάλληλα βίντεο για speed " + 
                       connectionSpeed + "Mbps και format " + format);
            
        } catch (NumberFormatException e) {
            out.println("ERROR:Λανθασμένη ταχύτητα σύνδεσης");
        }
    }
    
    private void handleStartStream(String[] parts, PrintWriter out) {
        if (parts.length < 3) {
            out.println("ERROR:Λανθασμένη μορφή αιτήματος");
            return;
        }
        
        String fileName = parts[1];
        String protocol = parts[2];
        
        try {
            // Ξεκίνησε το streaming σε background
            startVideoStreamingAsync(fileName, protocol);
            
            // Περίμενε λίγο για να ξεκινήσει ο server
            Thread.sleep(2000);
            
            out.println("STREAM_STARTED:" + fileName + ":" + protocol);
            logger.info("Ξεκίνησε streaming για: " + fileName + " με πρωτόκολλο " + protocol);
            
        } catch (Exception e) {
            out.println("ERROR:Σφάλμα εκκίνησης streaming: " + e.getMessage());
            logger.severe("Σφάλμα streaming: " + e.getMessage());
        }
    }
    
    private void startVideoStreamingAsync(String fileName, String protocol) {
        // Εκτέλεση σε ξεχωριστό thread για να μην μπλοκάρει τη σύνδεση
        new Thread(() -> {
            try {
                startVideoStreaming(fileName, protocol);
            } catch (Exception e) {
                logger.severe("Σφάλμα async streaming: " + e.getMessage());
            }
        }).start();
    }
    
    private void processVideos() {
        logger.info("Ξεκίνησε επεξεργασία βίντεο...");
        
        try {
            Path videosPath = Paths.get(VIDEOS_FOLDER);
            if (!Files.exists(videosPath)) {
                Files.createDirectories(videosPath);
                logger.info("Δημιουργήθηκε φάκελος videos");
                return;
            }
            
            // Καθαρισμός υπάρχουσας λίστας
            availableVideos.clear();
            
            // Εύρεση όλων των υπαρχόντων αρχείων
            Map<String, List<VideoFile>> existingFiles = new HashMap<>();
            
            Files.list(videosPath)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    VideoFile videoFile = parseVideoFile(file.getFileName().toString());
                    if (videoFile != null) {
                        existingFiles.computeIfAbsent(videoFile.movieName, k -> new ArrayList<>())
                                   .add(videoFile);
                    }
                });
            
            // Για κάθε ταινία, δημιουργία όλων των απαιτούμενων εκδόσεων
            for (Map.Entry<String, List<VideoFile>> entry : existingFiles.entrySet()) {
                String movieName = entry.getKey();
                List<VideoFile> existingVersions = entry.getValue();
                
                // Εύρεση της μέγιστης ανάλυσης που υπάρχει
                String maxResolution = findMaxResolution(existingVersions);
                
                // Δημιουργία όλων των απαιτούμενων εκδόσεων
                generateMissingVersions(movieName, existingVersions, maxResolution);
                
                // Ενημέρωση λίστας διαθέσιμων βίντεο
                availableVideos.put(movieName, getAllVersionsForMovie(movieName));
            }
            
            logger.info("Ολοκληρώθηκε επεξεργασία βίντεο. Συνολικά: " + getTotalVideoCount() + " αρχεία");
            
        } catch (IOException e) {
            logger.severe("Σφάλμα επεξεργασίας βίντεο: " + e.getMessage());
        }
    }
    
    private VideoFile parseVideoFile(String filename) {
        // Pattern για parsing: MovieName-ResolutionFormat (π.χ. Forrest_Gump-720p.mkv)
        Pattern pattern = Pattern.compile("(.+)-(\\d+p)\\.(avi|mp4|mkv)$");
        Matcher matcher = pattern.matcher(filename);
        
        if (matcher.matches()) {
            String movieName = matcher.group(1);
            String resolution = matcher.group(2);
            String format = "." + matcher.group(3);
            
            return new VideoFile(movieName, format, resolution, filename);
        }
        
        return null;
    }
    
    private String findMaxResolution(List<VideoFile> videos) {
        int maxResolutionValue = 0;
        String maxResolution = "240p";
        
        for (VideoFile video : videos) {
            int resolutionValue = Integer.parseInt(video.resolution.replace("p", ""));
            if (resolutionValue > maxResolutionValue) {
                maxResolutionValue = resolutionValue;
                maxResolution = video.resolution;
            }
        }
        
        return maxResolution;
    }
    
    private void generateMissingVersions(String movieName, List<VideoFile> existingVersions, String maxResolution) {
        Set<String> existingCombinations = new HashSet<>();
        VideoFile sourceFile = null;
        
        // Δημιουργία set με υπάρχουσες εκδόσεις
        for (VideoFile video : existingVersions) {
            existingCombinations.add(video.format + "-" + video.resolution);
            if (sourceFile == null || 
                Integer.parseInt(video.resolution.replace("p", "")) > 
                Integer.parseInt(sourceFile.resolution.replace("p", ""))) {
                sourceFile = video;
            }
        }
        
        if (sourceFile == null) return;
        
        int maxResolutionValue = Integer.parseInt(maxResolution.replace("p", ""));
        
        // Δημιουργία όλων των απαιτούμενων εκδόσεων
        for (String format : FORMATS) {
            for (String resolution : RESOLUTIONS) {
                int resolutionValue = Integer.parseInt(resolution.replace("p", ""));
                
                // Δεν δημιουργούμε ανάλυση μεγαλύτερη από τη μέγιστη υπάρχουσα
                if (resolutionValue > maxResolutionValue) {
                    continue;
                }
                
                String combination = format + "-" + resolution;
                if (!existingCombinations.contains(combination)) {
                    // Δημιουργία νέου αρχείου
                    generateVideoFile(sourceFile, movieName, format, resolution);
                }
            }
        }
    }
    
    private void generateVideoFile(VideoFile sourceFile, String movieName, String targetFormat, String targetResolution) {
        String outputFilename = movieName + "-" + targetResolution + targetFormat;
        String outputPath = VIDEOS_FOLDER + "/" + outputFilename;
        String sourcePath = VIDEOS_FOLDER + "/" + sourceFile.filename;
        
        try {
            // Έλεγχος αν το αρχείο υπάρχει ήδη
            if (Files.exists(Paths.get(outputPath))) {
                logger.info("Το αρχείο υπάρχει ήδη: " + outputFilename);
                return;
            }
            
            logger.info("Δημιουργία: " + outputFilename + " από " + sourceFile.filename);
            
            // Πραγματική μετατροπή με FFMPEG
            if (isFFMPEGAvailable()) {
                convertVideoWithFFMPEG(sourcePath, outputPath, targetFormat, targetResolution);
            } else {
                // Fallback σε dummy file αν δεν υπάρχει FFMPEG
                logger.warning("FFMPEG δεν είναι διαθέσιμο, δημιουργία dummy file");
                createDummyVideoFile(outputPath, targetFormat);
            }
            
            logger.info("Δημιουργήθηκε επιτυχώς: " + outputFilename);
            
        } catch (Exception e) {
            logger.warning("Σφάλμα δημιουργίας " + outputFilename + ": " + e.getMessage());
            // Fallback σε dummy file σε περίπτωση σφάλματος
            try {
                createDummyVideoFile(outputPath, targetFormat);
            } catch (IOException ex) {
                logger.severe("Αποτυχία δημιουργίας ακόμα και dummy file: " + ex.getMessage());
            }
        }
    }
    
    private boolean isFFMPEGAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void convertVideoWithFFMPEG(String inputPath, String outputPath, String targetFormat, String targetResolution) 
            throws IOException, InterruptedException {
        
        String scale = getScaleForResolution(targetResolution);
        
        List<String> command = Arrays.asList(
            "ffmpeg", "-i", inputPath,
            "-vf", "scale=" + scale,
            "-c:v", "libx264",
            "-c:a", "aac",
            "-preset", "fast",
            "-crf", "23",
            "-y", // Overwrite output file
            outputPath
        );
        
        logger.info("Εκτέλεση FFMPEG: " + String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        // Διάβασμα output για monitoring
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Εμφάνιση progress (προαιρετικά)
                if (line.contains("time=") || line.contains("frame=")) {
                    logger.fine("FFMPEG Progress: " + line);
                }
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFMPEG απέτυχε με κωδικό: " + exitCode);
        }
    }
    
    private void createDummyVideoFile(String outputPath, String format) throws IOException {
        // Δημιουργία ενός minimal valid video file για δοκιμή
        // Αυτό είναι για την άσκηση - στην πραγματικότητα θα χρησιμοποιείτε FFMPEG
        
        byte[] dummyContent;
        
        switch (format) {
            case ".mp4":
                // Minimal MP4 header
                dummyContent = new byte[]{
                    0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, // ftyp box
                    0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00,
                    0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                    0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31
                };
                break;
            case ".mkv":
                // Minimal MKV header (EBML)
                dummyContent = new byte[]{
                    0x1A, 0x45, (byte)0xDF, (byte)0xA3, // EBML signature
                    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F,
                    0x42, (byte)0x86, (byte)0x81, 0x01, 0x42, (byte)0xF7, (byte)0x81, 0x01,
                    0x42, (byte)0xF2, (byte)0x81, 0x04, 0x42, (byte)0xF3, (byte)0x81, 0x08
                };
                break;
            case ".avi":
            default:
                // Minimal AVI header
                dummyContent = new byte[]{
                    0x52, 0x49, 0x46, 0x46, // RIFF
                    0x00, 0x00, 0x00, 0x00, // File size (placeholder)
                    0x41, 0x56, 0x49, 0x20, // AVI 
                    0x4C, 0x49, 0x53, 0x54  // LIST
                };
                break;
        }
        
        Files.write(Paths.get(outputPath), dummyContent);
    }
    
    private String getScaleForResolution(String resolution) {
        switch (resolution) {
            case "240p": return "426:240";
            case "360p": return "640:360";
            case "480p": return "854:480";
            case "720p": return "1280:720";
            case "1080p": return "1920:1080";
            default: return "854:480";
        }
    }
    
    private List<VideoFile> getAllVersionsForMovie(String movieName) {
        List<VideoFile> versions = new ArrayList<>();
        
        try {
            Files.list(Paths.get(VIDEOS_FOLDER))
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    VideoFile video = parseVideoFile(file.getFileName().toString());
                    if (video != null && video.movieName.equals(movieName)) {
                        versions.add(video);
                    }
                });
        } catch (IOException e) {
            logger.warning("Σφάλμα ανάγνωσης αρχείων για " + movieName + ": " + e.getMessage());
        }
        
        return versions;
    }
    
    private List<VideoFile> getSuitableVideos(double connectionSpeedMbps, String format) {
        List<VideoFile> suitableVideos = new ArrayList<>();
        int connectionSpeedKbps = (int) (connectionSpeedMbps * 1000);
        
        for (List<VideoFile> movieVersions : availableVideos.values()) {
            for (VideoFile video : movieVersions) {
                if (video.format.equals(format) && 
                    MIN_BITRATES.get(video.resolution) <= connectionSpeedKbps) {
                    suitableVideos.add(video);
                }
            }
        }
        
        suitableVideos.sort((a, b) -> a.toString().compareTo(b.toString()));
        return suitableVideos;
    }
    
    private void startVideoStreaming(String fileName, String protocol) throws IOException {
        String inputPath = VIDEOS_FOLDER + "/" + fileName;
        
        // Έλεγχος αν το αρχείο υπάρχει
        if (!Files.exists(Paths.get(inputPath))) {
            throw new IOException("Το αρχείο δεν υπάρχει: " + inputPath);
        }
        
        List<String> command = new ArrayList<>();
        
        switch (protocol.toUpperCase()) {
            case "TCP":
                command.addAll(Arrays.asList(
                    "ffmpeg", "-re", "-i", inputPath,
                    "-c:v", "libx264", "-c:a", "aac", 
                    "-preset", "ultrafast", "-tune", "zerolatency",
                    "-g", "30", "-keyint_min", "30", // Keyframes κάθε δευτερόλεπτο
                    "-f", "mpegts", "tcp://localhost:9999?listen=1"
                ));
                break;
                
            case "UDP":
                command.addAll(Arrays.asList(
                    "ffmpeg", "-re", "-i", inputPath,
                    "-c:v", "libx264", "-c:a", "aac", 
                    "-preset", "ultrafast", "-tune", "zerolatency",
                    "-g", "15", "-keyint_min", "15", // Περισσότερα keyframes για UDP
                    "-x264opts", "nal-hrd=cbr", // Constant bitrate για καλύτερο UDP
                    "-b:v", "1000k", "-maxrate", "1000k", "-bufsize", "2000k",
                    "-f", "mpegts", "udp://localhost:9999?pkt_size=1316"
                ));
                break;
                
            case "RTP/UDP":
                // Για RTP χρειάζεται SDP file
                createSDPFile();
                command.addAll(Arrays.asList(
                    "ffmpeg", "-re", "-i", inputPath,
                    "-c:v", "libx264", "-c:a", "aac", 
                    "-preset", "ultrafast", "-tune", "zerolatency",
                    "-g", "30", "-keyint_min", "30",
                    "-f", "rtp", "rtp://localhost:9999"
                ));
                break;
                
            default:
                throw new IllegalArgumentException("Μη υποστηριζόμενο πρωτόκολλο: " + protocol);
        }
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        
        logger.info("Εκκίνηση FFMPEG streaming με εντολή: " + String.join(" ", command));
        
        // Εκκίνηση της διαδικασίας streaming σε ξεχωριστό thread
        new Thread(() -> {
            try {
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("time=") || line.contains("fps=") || line.contains("bitrate=")) {
                        logger.info("FFMPEG Streaming: " + line);
                    } else if (line.contains("error") || line.contains("Error")) {
                        logger.warning("FFMPEG Error: " + line);
                    }
                }
                int exitCode = process.waitFor();
                logger.info("FFMPEG streaming process τερμάτισε με κωδικό: " + exitCode);
            } catch (Exception e) {
                logger.severe("Σφάλμα FFMPEG streaming: " + e.getMessage());
            }
        }).start();
    }
    
    private void createSDPFile() {
        try {
            String sdpContent = 
                "v=0\n" +
                "o=- 0 0 IN IP4 127.0.0.1\n" +
                "s=Test Stream\n" +
                "c=IN IP4 127.0.0.1\n" +
                "t=0 0\n" +
                "m=video 9999 RTP/AVP 96\n" +
                "a=rtpmap:96 H264/90000\n";
            
            Files.write(Paths.get("stream.sdp"), sdpContent.getBytes());
            logger.info("Δημιουργήθηκε SDP file για RTP streaming");
        } catch (IOException e) {
            logger.warning("Σφάλμα δημιουργίας SDP file: " + e.getMessage());
        }
    }
    
    private int getTotalVideoCount() {
        return availableVideos.values().stream()
                             .mapToInt(List::size)
                             .sum();
    }
    
    // Inner class για αναπαράσταση αρχείου βίντεο
    private static class VideoFile {
        String movieName;
        String format;
        String resolution;
        String filename;
        
        public VideoFile(String movieName, String format, String resolution, String filename) {
            this.movieName = movieName;
            this.format = format;
            this.resolution = resolution;
            this.filename = filename;
        }
        
        @Override
        public String toString() {
            return movieName + "-" + resolution + format;
        }
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new StreamingServer();
        });
    }
}