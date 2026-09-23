package pl.bbqkees.nativehttp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.apache.cordova.CordovaPlugin;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class NativeHttpBridgePlugin extends CordovaPlugin {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile long lastRequestFinishedAt = 0L;
    private static final long MIN_REQUEST_GAP_MS = 1200L;

    @Override
    public void pluginInitialize() {
        try {
            Object engineView = webView.getEngine().getView();
            if (engineView instanceof WebView) {
                ((WebView) engineView).addJavascriptInterface(new Bridge(), "AndroidNative");
            }
        } catch (Exception e) {
            // brak mostu -> JS użyje fallbacku fetch()
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void request(final String requestId, final String method, final String url,
                             final String body, final String headersJson, final int timeoutMs) {
            executor.execute(() -> performRequest(requestId, method, url, body, headersJson, timeoutMs));
        }
    }

    private void performRequest(String requestId, String method, String urlText,
                                 String body, String headersJson, int timeoutMs) {
        HttpURLConnection conn = null;
        int status = 0;
        String responseText = "";
        String errorText = "";

        try {
            URI uri = new URI(urlText);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || host == null ||
                    !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new SecurityException("Dozwolone są tylko adresy HTTP/HTTPS.");
            }

            String upperMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);

            long wait = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestFinishedAt);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            URL url = uri.toURL();
            Network wifiNetwork = findWifiNetworkWithoutInternet();
            if (wifiNetwork != null) {
                conn = (HttpURLConnection) wifiNetwork.openConnection(url);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod(upperMethod);
            conn.setConnectTimeout(clamp(timeoutMs, 1000, 30000));
            conn.setReadTimeout(clamp(timeoutMs, 1000, 30000));
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "BBQKeesPro-Android/1.0");

            if (headersJson != null && !headersJson.isEmpty()) {
                JSONObject headers = new JSONObject(headersJson);
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String lower = key.toLowerCase(Locale.ROOT);
                    if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")) {
                        continue;
                    }
                    conn.setRequestProperty(key, headers.optString(key, ""));
                }
            }

            if ((upperMethod.equals("POST") || upperMethod.equals("PUT") || upperMethod.equals("PATCH"))
                    && body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                }
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }

            status = conn.getResponseCode();
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            responseText = readAll(input);

        } catch (Exception e) {
            errorText = e.getClass().getSimpleName() + ": " +
                    (e.getMessage() == null ? "błąd połączenia" : e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            lastRequestFinishedAt = System.currentTimeMillis();
        }

        sendResponseToJs(requestId, status, responseText, errorText);
    }

    private void sendResponseToJs(String requestId, int status, String body, String error) {
        final String js =
                "window.__nativeHttpResponse(" +
                        JSONObject.quote(requestId == null ? "" : requestId) + "," +
                        status + "," +
                        JSONObject.quote(body == null ? "" : body) + "," +
                        JSONObject.quote(error == null ? "" : error) +
                        ");";

        cordova.getActivity().runOnUiThread(() -> {
            try {
                webView.getEngine().evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    private Network findWifiNetworkWithoutInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    cordova.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Network> result = new AtomicReference<>();

            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    result.set(network);
                    latch.countDown();
                }
            };

            cm.requestNetwork(request, callback);
            latch.await(2500, TimeUnit.MILLISECONDS);

            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }

            return result.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
                if (sb.length() > 2_000_000) {
                    throw new IllegalStateException("Odpowiedź HTTP jest zbyt duża.");
                }
            }
        }
        return sb.toString();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
