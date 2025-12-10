package com.cordova.geckoview;

import android.net.Uri;
import android.text.TextUtils;

import org.apache.cordova.CordovaResourceApi;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal embedded HTTP server that serves files from Cordova's ResourceApi.
 */
class LocalHttpServer {

    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String APP_PREFIX = "/_app_file_";
    private static final String CDV_PREFIX = "/_cdvfile_/";
    private static final String DEFAULT_APP_BASE = "file:///android_asset/www/";

    private final CordovaResourceApi resourceApi;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private String baseUrl;
    private final String appBase;

    LocalHttpServer(CordovaResourceApi resourceApi, String appBasePath) {
        this.resourceApi = resourceApi;
        String canonicalBase = TextUtils.isEmpty(appBasePath) ? DEFAULT_APP_BASE : appBasePath;
        if (!canonicalBase.endsWith("/")) {
            canonicalBase += "/";
        }
        this.appBase = canonicalBase;
    }

    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(0, 0, InetAddress.getByName(null));
        running = true;
        baseUrl = String.format(Locale.US, "http://%s:%d", LOCAL_HOST, serverSocket.getLocalPort());
        acceptThread = new Thread(this::acceptLoop, "GeckoAssetServer");
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                executor.execute(() -> handleClient(client));
            } catch (SocketException se) {
                // Socket closed during shutdown.
                break;
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        executor.shutdownNow();
    }

    String getBaseUrl() {
        return baseUrl;
    }

    String rewriteUri(String url) {
        if (TextUtils.isEmpty(url) || baseUrl == null) {
            return url;
        }
        if (url.startsWith("javascript:")) {
            return url;
        }
        if (url.startsWith("cdvfile://")) {
            return baseUrl + CDV_PREFIX + Uri.encode(url);
        }
        if (url.startsWith(appBase)) {
            String rel = url.substring(appBase.length());
            return joinUrl(rel);
        }
        if (url.startsWith("file:///android_asset/www/")) {
            String rel = url.substring("file:///android_asset/www/".length());
            return joinUrl(rel);
        }
        return url;
    }

    private String joinUrl(String relative) {
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return baseUrl + "/" + relative;
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream rawOut = new BufferedOutputStream(client.getOutputStream())) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendStatus(rawOut, "400 Bad Request", "Malformed request");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            // Consume and ignore request headers for now.
            String headerLine;
            while ((headerLine = reader.readLine()) != null && headerLine.length() > 0) {
                // no-op
            }

            if (!"GET".equalsIgnoreCase(method)) {
                sendStatus(rawOut, "405 Method Not Allowed", "Only GET supported");
                return;
            }

            servePath(rawOut, path);
        } catch (IOException e) {
            // Ignore broken pipe etc.
        }
    }

    private void servePath(OutputStream out, String rawPath) throws IOException {
        Uri target = resolveTarget(rawPath);
        if (target == null) {
            sendStatus(out, "404 Not Found", "Not Found");
            return;
        }
        CordovaResourceApi.OpenForReadResult result;
        try {
            Uri remapped = resourceApi.remapUri(target);
            result = resourceApi.openForRead(remapped != null ? remapped : target);
        } catch (FileNotFoundException e) {
            sendStatus(out, "404 Not Found", "Not Found");
            return;
        }

        String mimeType = result.mimeType;
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = resourceApi.getMimeType(target);
        }
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "application/octet-stream";
        }

        long length = result.length;
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n");
        headers.append("Content-Type: ").append(mimeType).append("\r\n");
        if (length >= 0) {
            headers.append("Content-Length: ").append(length).append("\r\n");
        }
        headers.append("Access-Control-Allow-Origin: *\r\n");
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));

        try (InputStream is = result.inputStream) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.flush();
    }

    private Uri resolveTarget(String path) {
        if (TextUtils.isEmpty(path)) {
            path = "/";
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.startsWith(CDV_PREFIX)) {
            String encoded = path.substring(CDV_PREFIX.length());
            String decoded = Uri.decode(encoded);
            return Uri.parse(decoded);
        }
        String relative;
        if (path.startsWith(APP_PREFIX)) {
            relative = path.substring(APP_PREFIX.length());
        } else {
            relative = path;
        }
        if (TextUtils.isEmpty(relative) || "/".equals(relative)) {
            relative = "index.html";
        } else if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return Uri.parse(appBase + relative);
    }

    private void sendStatus(OutputStream out, String status, String message) throws IOException {
        String body = message == null ? "" : message;
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
