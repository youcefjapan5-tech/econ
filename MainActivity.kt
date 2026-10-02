package app.videowall

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    // طلب الأذونات أولاً ثم فتح الواجهة
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { !it }) Toast.makeText(this, "لم تُمنح أذونات الوصول إلى الفيديو؛ قد لا تعمل بعض الميزات", Toast.LENGTH_LONG).show()
        startUi()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) startUi() else perms.launch(arrayOf(p))
    }

    private fun startUi() {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest) = loader.shouldInterceptRequest(req.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    inner class Bridge {
        private var os: FileOutputStream? = null
        private var name = ""

        @JavascriptInterface fun begin(ext: String) {
            filesDir.listFiles()?.filter { it.name.startsWith("wall_") }?.forEach { it.delete() }
            name = "wall_${System.currentTimeMillis()}.$ext"
            os = FileOutputStream(File(filesDir, name))
        }
        @JavascriptInterface fun chunk(b64: String) { os?.write(Base64.decode(b64, Base64.DEFAULT)) }
        @JavascriptInterface fun finish() {
            os?.close(); os = null
            getSharedPreferences("wall", 0).edit().putString("f", name).apply()
            runOnUiThread {
                // شاشة النظام: معاينة + زر "تعيين كخلفية"
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, VideoWallpaperService::class.java)))
            }
        }
    }
}
