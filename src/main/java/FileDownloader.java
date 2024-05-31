import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "FileDownloader", mixinStandardHelpOptions = true, version = "1.0",
        description = "Downloads a file from a given URL.")
public class FileDownloader implements Callable<Integer> {

    @Option(names = {"-u", "--url"}, required = true, description = "The URL of the file to download.")
    private String fileUrl;

    @Option(names = {"-l", "--licenseUrl"}, required = false, description = "The URL to accept the license agreement before downloading.")
    private String licenseUrl;

    @Option(names = {"-d", "--destination"}, required = true, description = "The destination path for the downloaded file.")
    private String destinationPath;

    @Option(names = {"-p", "--progressLog"}, required = true, description = "The path for the progress log file.")
    private String progressLogPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new FileDownloader()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // Ensure progress log file is cleared before starting
        Files.write(Paths.get(progressLogPath), new byte[0]);

        if (licenseUrl != null && !licenseUrl.isEmpty()) {
            acceptLicenseAgreement(licenseUrl);
        }

        downloadFile(fileUrl, destinationPath, progressLogPath);
        System.out.println("\nDownload completed successfully.");
        return 0;
    }

    private void acceptLicenseAgreement(String licenseUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(licenseUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Failed to accept license agreement. Server returned HTTP code: " + responseCode);
            }
            System.out.println("License agreement accepted.");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void downloadFile(String fileUrl, String destinationPath, String progressLogPath) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        int fileSize = connection.getContentLength(); // Get the size of the file

        if (fileSize <= 0) { // Handle unknown file size case
            System.err.println("Warning: File size is unknown. Progress won't be calculated accurately.");
        }

        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             FileOutputStream out = new FileOutputStream(destinationPath);
             ProgressLogger logger = new ProgressLogger(progressLogPath, fileSize)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                logger.logProgress(bytesRead);
            }
        }
    }

    private static class ProgressLogger implements AutoCloseable {
        private final FileOutputStream logFile;
        private final int totalBytes;
        private int bytesWritten;

        public ProgressLogger(String logFilePath, int totalBytes) throws IOException {
            this.logFile = new FileOutputStream(logFilePath, true);
            this.totalBytes = totalBytes;
            this.bytesWritten = 0;
        }

        private static String humanReadableByteCountBin(long bytes) {
            long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
            if (absB < 1024L) {
                return bytes + " B";
            }
            long value = absB;
            CharacterIterator ci = new StringCharacterIterator("KMGTPE");
            for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
                value >>= 10;
                ci.next();
            }
            value *= Long.signum(bytes);
            return String.format("%.1f %cB", value / 1024.0, ci.current());
        }

        public synchronized void logProgress(int bytesIncrement) throws IOException {
            bytesWritten += bytesIncrement;
            double progress = totalBytes > 0 ? (double) bytesWritten / totalBytes * 100 : -1;

            String progressPercentage = progress >= 0 ? String.format("%.2f%%", progress) : "N/A";
            String progressMessage = String.format("\rDownload Progress: %s (%s of %s)",
                    progressPercentage, humanReadableByteCountBin(bytesWritten), totalBytes > 0 ? humanReadableByteCountBin(totalBytes) : "Unknown size");

            logFile.write((progressMessage + "\n").getBytes());

            // Print the progress message on the console, replacing the same line
            System.out.print(progressMessage);
        }

        @Override
        public void close() throws IOException {
            logFile.close();
        }
    }
}
