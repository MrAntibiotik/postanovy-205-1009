package ua.mrantibiotik.postanovy;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String START_URL = "file:///android_asset/index.html";
    private static final int REQ_OPEN_FILE = 1;
    private static final int REQ_SAVE_FILE = 2;

    /*
     * WebView не вміє зберігати blob-посилання з атрибутом download (кнопка «Експорт прогресу»).
     * Перехоплюємо такий клік, читаємо вміст blob і передаємо його в застосунок,
     * який пропонує обрати місце збереження через системний діалог.
     */
    private static final String EXPORT_HOOK =
            "(function(){if(window.__exportHook)return;window.__exportHook=true;" +
            "var blobs={},mk=URL.createObjectURL;" +
            "URL.createObjectURL=function(b){var u=mk.call(URL,b);blobs[u]=b;return u;};" +
            "var orig=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){" +
            "var b=this.download&&blobs[this.href];" +
            "if(b){var name=this.download,fr=new FileReader();" +
            "fr.onload=function(){AndroidBridge.saveFile(name,fr.result);};" +
            "fr.readAsText(b);return;}" +
            "return orig.apply(this,arguments);};})();";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSaveText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // прогрес зберігається в localStorage

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                // Зовнішні посилання (zakon.rada.gov.ua тощо) — у браузері
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(EXPORT_HOOK, null);
            }
        });

        // Стандартний WebChromeClient показує діалоги confirm()/alert() (кнопка «Скинути прогрес»)
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // Імпорт прогресу з файлу
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String text) {
            runOnUiThread(() -> {
                pendingSaveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingSaveText = null;
                    Toast.makeText(MainActivity.this, "Не вдалося відкрити діалог збереження", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE) {
            if (fileCallback != null) {
                Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
                fileCallback.onReceiveValue(uri != null ? new Uri[]{uri} : null);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            String text = pendingSaveText;
            pendingSaveText = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || text == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Файл прогресу збережено", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Помилка збереження файлу", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
