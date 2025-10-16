package com.packet.startupanalystxi;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.content.ContentValues;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Set;
import java.util.HashSet;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    String websiteURL = "https://startup-analyst-xi.vercel.app/"; // sets web url
    private WebView webview;

    SwipeRefreshLayout mySwipeRefreshLayout;

    private ValueCallback<Uri[]> filePathCallback; // For <input type="file">
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    // Download notification/channel data
    private static final String DL_CHANNEL_ID = "downloads_channel";
    private final Set<Long> activeDownloadIds = new HashSet<>();
    private BroadcastReceiver downloadReceiver;
    private static final String FIXED_FILE_NAME = "vc-analysis.pdf";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Webview stuff
        webview = findViewById(R.id.webView);
        webview.getSettings().setJavaScriptEnabled(true);
        webview.getSettings().setDomStorageEnabled(true);
        // Create notification channel for download completion
        createDownloadNotificationChannel();
        // Allow mixed content (if your site serves downloads via http)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webview.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        // Ensure cookies (incl. 3rd party) are available for downloads
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webview, true);
            }
        } catch (Throwable ignored) {}
        // JS bridge to handle blob: downloads
        webview.addJavascriptInterface(new DownloadBridge(), "AndroidDownloader");
        webview.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webview.loadUrl(websiteURL);
        webview.setWebViewClient(new WebViewClientDemo());

        // Handle <input type="file"> from the website
        setupFileUploadSupport();

        //Swipe to refresh functionality
        mySwipeRefreshLayout = (SwipeRefreshLayout)this.findViewById(R.id.swipeContainer);

        mySwipeRefreshLayout.setOnRefreshListener(
                new SwipeRefreshLayout.OnRefreshListener() {
                    @Override
                    public void onRefresh() {
                        webview.reload();
                    }
                }
        );
        // Request permissions conditionally by Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For Android 9 (API 28) and below, request WRITE_EXTERNAL_STORAGE for public Downloads
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                    Log.d("permission", "permission denied to WRITE_EXTERNAL_STORAGE - requesting it");
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                }
            }
            // For Android 13+ (API 33), request notification permission so DownloadManager can show completion notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
                    }
                } catch (Throwable ignored) {
                    // On older devices/compilers, POST_NOTIFICATIONS may not exist – ignore
                }
            }
        }

//handle downloading

        webview.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                // Handle blob: URLs which DownloadManager cannot process
                if (url != null && url.startsWith("blob:")) {
                    String fileName = FIXED_FILE_NAME;
                    final String safeName = FIXED_FILE_NAME;
                    final String safeMime = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
            String js = "(function(){\n" +
                "  var u='" + url + "';\n" +
                "  fetch(u).then(function(r){\n" +
                "    var cd = r.headers.get('content-disposition') || '';\n" +
                "    return r.blob().then(function(b){ return {blob:b, cd:cd}; });\n" +
                "  }).then(function(obj){\n" +
                "    var b = obj.blob;\n" +
                "    var cd = obj.cd;\n" +
                "    var rd=new FileReader();\n" +
                "    rd.onloadend=function(){\n" +
                "      var base64=rd.result.split(',')[1];\n" +
                "      try{ window.AndroidDownloader.saveFile(base64,'" + safeName + "','" + safeMime + "', cd); }catch(e){console.error(e);}\n" +
                "    };\n" +
                "    rd.readAsDataURL(b);\n" +
                "  }).catch(function(e){console.error(e);});\n" +
                "})();";
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        webview.evaluateJavascript(js, null);
                    } else {
                        webview.loadUrl("javascript:" + js);
                    }
                    return;
                }
                try {
                    Uri uri = Uri.parse(url);
                    String fileName = FIXED_FILE_NAME;

                    DownloadManager.Request request = new DownloadManager.Request(uri);
                    if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) request.addRequestHeader("cookie", cookies);
                    if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
                    try { request.addRequestHeader("Referer", websiteURL); } catch (Throwable ignored) {}
                    request.setDescription("Downloading file…");
                    request.setTitle(FIXED_FILE_NAME);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                    // Allow over metered/roaming networks
                    request.setAllowedOverMetered(true);
                    request.setAllowedOverRoaming(true);

                    // Choose a destination compatible across Android versions
                    boolean destinationSet = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // On Android 10+, DownloadManager can write to public Downloads without storage permission
                        try {
                            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FIXED_FILE_NAME);
                            destinationSet = true;
                        } catch (Throwable t) {
                            // Fallback to app-specific Downloads
                        }
                    } else {
                        // On Android 9 and below, use public Downloads if we have permission
                        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FIXED_FILE_NAME);
                            destinationSet = true;
                        }
                    }
                    if (!destinationSet) {
                        // App-specific Downloads directory as a safe fallback
                        request.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, FIXED_FILE_NAME);
                    }

                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        long id = dm.enqueue(request);
                        // Track this download locally and in SharedPreferences for manifest receiver
                        activeDownloadIds.add(id);
                        try {
                            android.content.SharedPreferences sp = getSharedPreferences("downloads_prefs", MODE_PRIVATE);
                            java.util.Set<String> set = new java.util.HashSet<>(sp.getStringSet("pending_ids", new java.util.HashSet<>()));
                            set.add(Long.toString(id));
                            sp.edit().putStringSet("pending_ids", set).apply();
                        } catch (Exception ignored) {}
                        ensureDownloadReceiverRegistered();
                        Toast.makeText(getApplicationContext(), "Downloading file", Toast.LENGTH_SHORT).show();
                    } else {
                        throw new IllegalStateException("DownloadManager unavailable");
                    }
                } catch (Exception e) {
                    // As a last resort, try to open the URL in an external app (e.g., browser)
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(i);
                    } catch (Exception ex) {
                        Toast.makeText(getApplicationContext(), "Unable to download file.", Toast.LENGTH_LONG).show();
                        Log.e("Download", "Failed to start download", e);
                    }
                }
            }
        });

    }
    private void setupFileUploadSupport() {
        // Register Activity Result for file chooser
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                (ActivityResult result) -> {
                    if (filePathCallback == null) return;

                    Uri[] results = null;
                    try {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Intent data = result.getData();
                            if (data.getClipData() != null) {
                                // Multiple files selected
                                final int count = data.getClipData().getItemCount();
                                results = new Uri[count];
                                for (int i = 0; i < count; i++) {
                                    results[i] = data.getClipData().getItemAt(i).getUri();
                                }
                            } else if (data.getData() != null) {
                                // Single file selected
                                results = new Uri[]{ data.getData() };
                            }
                        }
                    } catch (Exception ignored) {
                        // If anything goes wrong, return null to signal cancel
                    }

                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );

        webview.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                // Reset any existing callback
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent chooserIntent;
                try {
                    // Prefer the intent provided by WebView if possible
                    chooserIntent = fileChooserParams.createIntent();
                } catch (ActivityNotFoundException e) {
                    // Fallback to a generic GET_CONTENT if the system can't handle the provided intent
                    Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    // Respect accept types if provided, otherwise allow any
                    String[] types = fileChooserParams != null ? fileChooserParams.getAcceptTypes() : null;
                    boolean hasTypes = types != null && types.length > 0 && !(types.length == 1 && (types[0] == null || types[0].isEmpty()));
                    if (hasTypes) {
                        // If multiple types, set wildcard and pass list via EXTRA_MIME_TYPES
                        contentIntent.setType("*/*");
                        contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                    } else {
                        contentIntent.setType("*/*");
                    }
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams != null && fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    chooserIntent = Intent.createChooser(contentIntent, "Select file");
                }

                try {
                    fileChooserLauncher.launch(chooserIntent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No file chooser found.", Toast.LENGTH_LONG).show();
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    private void ensureDownloadReceiverRegistered() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == -1) return;
                if (!activeDownloadIds.contains(id)) return; // only notify for our downloads
                activeDownloadIds.remove(id);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) return;
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                try (android.database.Cursor c = dm.query(q)) {
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                        String mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri fileUri = dm.getUriForDownloadedFile(id);
                            // Build a user-friendly location string
                            String locationText = "Downloads/" + (title != null ? title : "file");
                            showDownloadNotification(title != null ? title : "File downloaded", locationText, fileUri, mime);
                        } else {
                            showDownloadNotification("Download failed", title != null ? title : "File", null, null);
                        }
                    }
                } catch (Exception e) {
                    Log.e("Download", "Error querying download", e);
                }
            }
        };
        registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    DL_CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Download status and completion");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void showDownloadNotification(String title, String location, Uri fileUri, String mimeType) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, DL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(location)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (fileUri != null) {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(fileUri, (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, (int) (System.currentTimeMillis() & 0xffff), viewIntent, flags);
            builder.setContentIntent(pi);
        }

        NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
    }

    private class WebViewClientDemo extends WebViewClient {
        @Override
        //Keep webview in app when clicking links
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            mySwipeRefreshLayout.setRefreshing(false);
        }
    }

    //set back button functionality
    @Override
    public void onBackPressed() { //if user presses the back button do this
        if (webview.isFocused() && webview.canGoBack()) { //check if in webview and the user can go back
            webview.goBack(); //go back in webview
        } else { //do this if the webview cannot go back any further

            new AlertDialog.Builder(this) //alert the person knowing they are about to close
                    .setTitle("EXIT")
                    .setMessage("Are you sure. You want to close this app?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
        }
    }

    // Bridge used by JS to persist blob: downloads
    private class DownloadBridge {
        @JavascriptInterface
        public void saveFile(String base64Data, String fileName, String mimeType, String contentDisposition) {
            runOnUiThread(() -> {
                try {
                    byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                    Uri notifyUri = null;
                    // Prefer filename from Content-Disposition if available
                    String chosenName = FIXED_FILE_NAME;
                    try {
                        String fromCd = getFileNameFromContentDisposition(contentDisposition);
                        if (fromCd != null && !fromCd.trim().isEmpty()) chosenName = fromCd;
                    } catch (Throwable ignored) {}
                    // Ensure extension exists; if not, try to add one from mimeType
                    try {
                        if (chosenName != null && !chosenName.contains(".")) {
                            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                            if (ext != null && !ext.isEmpty()) chosenName = chosenName + "." + ext;
                        }
                    } catch (Throwable ignored) {}
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, chosenName);
                        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        values.put(MediaStore.Downloads.IS_PENDING, 1);
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) throw new IOException("Failed to create MediaStore record");
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os == null) throw new IOException("Failed to open output stream");
                            os.write(data);
                        }
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                        notifyUri = uri;
                    } else {
                        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!downloads.exists()) downloads.mkdirs();
                        File out = new File(downloads, chosenName);
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(data);
                        }
                        // For file:// URIs; note that opening may require a FileProvider for SDK >= 24.
                        notifyUri = Uri.fromFile(out);
                    }
                    Toast.makeText(getApplicationContext(), "File saved to Downloads", Toast.LENGTH_LONG).show();

                    // Also show a system notification with the saved location and open action
                    try {
                        String locationText = "Downloads/" + chosenName;
                        showDownloadNotification("File downloaded", locationText, notifyUri, (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType);
                    } catch (Throwable ignored) {
                        // If notifications are disabled or not permitted, at least the toast above informs the user
                    }
                } catch (Exception e) {
                    Log.e("DownloadBridge", "Failed to save file", e);
                    Toast.makeText(getApplicationContext(), "Failed to save file", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // Parse Content-Disposition to extract filename or filename*
    private String getFileNameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null) return null;
        try {
            String cd = contentDisposition;
            // filename*=UTF-8''encodedname
            int idx = cd.indexOf("filename*=",
                    0);
            if (idx >= 0) {
                String part = cd.substring(idx + 9).trim();
                int semi = part.indexOf(';');
                if (semi > 0) part = part.substring(0, semi);
                // remove optional charset'' prefix
                int q = part.indexOf('\'');
                if (q >= 0) {
                    // format: UTF-8''%e2%82%ac%20rates.pdf
                    int q2 = part.indexOf('\'', q + 1);
                    if (q2 >= 0) {
                        String encoded = part.substring(q2 + 1).trim();
                        encoded = encoded.replaceAll("\"", "");
                        return URLDecoder.decode(encoded, "UTF-8");
                    }
                }
                // fallback: decode whole
                part = part.replaceAll("\"", "");
                return URLDecoder.decode(part, "UTF-8");
            }

            // filename="name.pdf" or filename=name.pdf
            idx = cd.indexOf("filename=");
            if (idx >= 0) {
                String part = cd.substring(idx + 9).trim();
                int semi = part.indexOf(';');
                if (semi > 0) part = part.substring(0, semi);
                part = part.trim();
                if (part.startsWith("\"") && part.endsWith("\"")) {
                    part = part.substring(1, part.length() - 1);
                }
                return part;
            }
        } catch (Throwable ignored) {}
        return null;
    }

}