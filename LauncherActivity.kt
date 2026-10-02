package app.videowall

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * شاشة رئيسية (Launcher) ترسم أيقونات التطبيقات الحقيقية وتتيح تخصيص شكلها وصوراتها وأبعادها.
 * لتفعيل الأيقونات فعلياً يجب تعيين هذا النشاط كتطبيق «الشاشة الرئيسية» الافتراضي.
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var store: IconStore
    private lateinit var grid: GridLayout
    private val cache = HashMap<String, Bitmap>()
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private data class App(val pkg: String, val label: String)

    private var apps: List<App> = emptyList()
    private var columns = 4

    // حالة حوار التخصيص الحالي
    private var editingPkg: String? = null
    private var dialogBaseSrc: Bitmap? = null   // أيقونة التطبيق الأصلية
    private var dialogCustomSrc: Bitmap? = null // الصورة المخصصة المختارة (إن وُجدت)
    private var pendingTemp: File? = null       // صورة مؤقته تُنقل عند الحفظ فقط
    private var activeDialog: AlertDialog? = null
    private var activePreviewUpdater: (() -> Unit)? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked(uri)
    }
    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeButton()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_launcher)
        store = IconStore(this)
        grid = findViewById(R.id.grid)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadApps() }
        findViewById<Button>(R.id.btnGlobal).setOnClickListener { openEditor(null) }
        findViewById<Button>(R.id.btnHome).setOnClickListener { requestHomeRole() }

        columns = (resources.displayMetrics.widthPixels / dp(76)).coerceIn(3, 6)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshHomeButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    // ---------- تحميل التطبيقات ورسم الشبكة ----------

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        apps = list.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) return@mapNotNull null // استبعاد تطبيقنا
            App(pkg, pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString())
        }.distinctBy { it.pkg }.sortedBy { it.label }
        buildGrid()
    }

    private fun buildGrid() {
        grid.removeAllViews()
        grid.columnCount = columns
        val cellW = resources.displayMetrics.widthPixels / columns
        for (app in apps) {
            val cell = View.inflate(this, R.layout.item_app, null) as android.widget.LinearLayout
            val iv = cell.findViewById<ImageView>(R.id.icon)
            val tv = cell.findViewById<TextView>(R.id.label)
            tv.text = app.label
            tv.setShadowLayer(3f, 0f, 1f, Color.argb(200, 0, 0, 0))

            applyCellSize(iv, store.forPkg(app.pkg).sizeDp)
            iv.setImageBitmap(cache[app.pkg])

            cell.setOnClickListener { launch(app.pkg) }
            cell.setOnLongClickListener { openEditor(app.pkg); true }

            val lp = GridLayout.LayoutParams()
            lp.width = cellW
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT
            cell.layoutParams = lp
            grid.addView(cell)

            renderInto(app.pkg, iv)
        }
    }

    private fun applyCellSize(iv: ImageView, sizeDp: Int) {
        val px = dp(sizeDp)
        iv.layoutParams = android.widget.LinearLayout.LayoutParams(px, px).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    /** يرسم أيقونة التطبيق (بالشكل/الصورة/الأبعاد المحفوظة) في خلفية ثم يعرضها. */
    private fun renderInto(pkg: String, iv: ImageView) {
        val cfg = store.forPkg(pkg)
        executor.execute {
            val bmp = renderIcon(pkg, cfg)
            main.post {
                cache[pkg] = bmp
                applyCellSize(iv, cfg.sizeDp)
                iv.setImageBitmap(bmp)
            }
        }
    }

    private fun renderIcon(pkg: String, cfg: IconStore.Cfg): Bitmap {
        val src = sourceFor(pkg)
        val px = dp(cfg.sizeDp).coerceAtLeast(48)
        return IconShape.apply(src, px, cfg.shape, cfg.radius)
    }

    /** الصورة الفعّالة: المخصّصة إن وُجدت، وإلا أيقونة التطبيق الأصلية. */
    private fun sourceFor(pkg: String): Bitmap {
        val f = store.imageFile(pkg)
        if (f.exists()) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) return b
        }
        return appIcon(pkg)
    }

    private fun appIcon(pkg: String): Bitmap {
        return try {
            IconShape.drawableToBitmap(packageManager.getApplicationIcon(pkg))
        } catch (e: Exception) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            i.addCategory(Intent.CATEGORY_LAUNCHER)
            startActivity(i)
        } else {
            Toast.makeText(this, "لا يمكن فتح التطبيق", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- حوار التخصيص ----------

    private fun openEditor(pkg: String?) {
        editingPkg = pkg
        val isGlobal = pkg == null

        val view = View.inflate(this, R.layout.dialog_edit_icon, null)
        val title = view.findViewById<TextView>(R.id.tvTitle)
        val preview = view.findViewById<ImageView>(R.id.preview)
        val shapes = view.findViewById<RadioGroup>(R.id.shapes)
        val sbCorner = view.findViewById<SeekBar>(R.id.sbCorner)
        val sbSize = view.findViewById<SeekBar>(R.id.sbSize)
        val lblCorner = view.findViewById<TextView>(R.id.lblCorner)
        val lblSize = view.findViewById<TextView>(R.id.lblSize)
        val btnPick = view.findViewById<Button>(R.id.btnPick)
        val btnRemove = view.findViewById<Button>(R.id.btnRemove)
        val cbApplyAll = view.findViewById<CheckBox>(R.id.cbApplyAll)

        val startCfg = if (isGlobal) store.global() else store.forPkg(pkg!!)
        title.text = if (isGlobal) getString(R.string.global_settings) else (appName(pkg!!) + " — " + getString(R.string.edit_icon))

        dialogBaseSrc = if (isGlobal) apps.firstOrNull()?.let { appIcon(it.pkg) } else appIcon(pkg!!)
        dialogCustomSrc = if (isGlobal || pkg == null) null else loadSavedCustom(pkg)
        pendingTemp = null

        // حالة أولية
        selectShape(shapes, startCfg.shape)
        sbCorner.progress = (startCfg.radius * 100).toInt().coerceIn(0, 50)
        sbSize.progress = (startCfg.sizeDp - 48).coerceIn(0, 112)

        // للإعداد العام لا معنى لاختيار صورة
        btnPick.visibility = if (isGlobal) View.GONE else View.VISIBLE
        btnRemove.visibility = if (isGlobal) View.GONE else View.VISIBLE

        fun currentShape(): IconShape.Shape = when (shapes.checkedRadioButtonId) {
            R.id.shNone -> IconShape.Shape.NONE
            R.id.shCircle -> IconShape.Shape.CIRCLE
            R.id.shSquircle -> IconShape.Shape.SQUIRCLE
            R.id.shSquare -> IconShape.Shape.SQUARE
            else -> IconShape.Shape.ROUNDED
        }
        fun updatePreview() {
            val sh = currentShape()
            lblCorner.visibility = if (sh == IconShape.Shape.ROUNDED || sh == IconShape.Shape.SQUIRCLE) View.VISIBLE else View.GONE
            sbCorner.visibility = lblCorner.visibility
            val sizeDp = sbSize.progress + 48
            lblSize.text = getString(R.string.icon_size) + ": ${sizeDp}dp"
            val src = dialogCustomSrc ?: dialogBaseSrc
            if (src != null) {
                val out = IconShape.apply(src, dp(sizeDp).coerceIn(96, 240), sh, sbCorner.progress / 100f)
                preview.setImageBitmap(out)
                preview.visibility = View.VISIBLE
            } else {
                preview.visibility = View.GONE
            }
        }
        shapes.setOnCheckedChangeListener { _, _ -> updatePreview() }
        sbCorner.setOnSeekBarChangeListener(seekListener { updatePreview() })
        sbSize.setOnSeekBarChangeListener(seekListener { updatePreview() })
        updatePreview()

        btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnRemove.setOnClickListener {
            dialogCustomSrc = null
            pendingTemp?.delete(); pendingTemp = null
            updatePreview()
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        activeDialog = dialog
        activePreviewUpdater = { updatePreview() }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            pendingTemp?.delete(); pendingTemp = null
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnReset).setOnClickListener {
            if (!isGlobal && pkg != null) {
                store.deleteImage(pkg)
                store.clearPkg(pkg)
                cache.remove(pkg)
                refreshAll()
            }
            pendingTemp?.delete(); pendingTemp = null
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val sh = currentShape()
            val radius = sbCorner.progress / 100f
            val sizeDp = sbSize.progress + 48

            if (cbApplyAll.isChecked || isGlobal) {
                store.applyShapeToAll(sh, radius, sizeDp)
            }

            if (!isGlobal && pkg != null) {
                // نقل الصورة المؤقتة إلى ملف الأيقونة عند الحفظ فقط
                val tmp = pendingTemp
                if (tmp != null && tmp.exists()) {
                    tmp.copyTo(store.imageFile(pkg), overwrite = true)
                    tmp.delete()
                    pendingTemp = null
                } else if (dialogCustomSrc == null) {
                    store.imageFile(pkg).takeIf { it.exists() }?.delete()
                }
                val cfg = IconStore.Cfg(sh, radius, sizeDp,
                    if (store.imageFile(pkg).exists()) store.imageFile(pkg).absolutePath else null)
                store.setPkg(pkg, cfg)
                cache.remove(pkg)
            }

            pendingTemp?.delete(); pendingTemp = null
            refreshAll()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun onImagePicked(uri: Uri) {
        val pkg = editingPkg ?: return
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(input)
            input.close()
            if (bmp == null) return
            val tmp = File(cacheDir, "tmp_$pkg.png")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pendingTemp = tmp
            dialogCustomSrc = bmp
            activePreviewUpdater?.invoke()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر تحميل الصورة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSavedCustom(pkg: String): Bitmap? {
        val f = store.imageFile(pkg)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun appName(pkg: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (e: PackageManager.NameNotFoundException) { pkg }

    private fun selectShape(group: RadioGroup, shape: IconShape.Shape) {
        val id = when (shape) {
            IconShape.Shape.NONE -> R.id.shNone
            IconShape.Shape.CIRCLE -> R.id.shCircle
            IconShape.Shape.SQUARE -> R.id.shSquare
            IconShape.Shape.SQUIRCLE -> R.id.shSquircle
            IconShape.Shape.ROUNDED -> R.id.shRounded
        }
        group.check(id)
    }

    private fun refreshAll() {
        cache.clear()
        buildGrid()
    }

    // ---------- دور الشاشة الرئيسية ----------

    private var homeHintShown = false
    private fun refreshHomeButton() {
        val btn = findViewById<Button>(R.id.btnHome)
        val isHome = isDefaultHome()
        btn.text = if (isHome) "✓ التطبيق الرئيسي" else getString(R.string.set_home)
        if (!isHome && !homeHintShown) {
            homeHintShown = true
            Toast.makeText(this, getString(R.string.home_hint), Toast.LENGTH_LONG).show()
        }
    }

    private fun isDefaultHome(): Boolean = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        info?.activityInfo?.packageName == packageName
    } catch (e: Exception) { false }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                homeRole.launch(rm.createRequestHomeRoleIntent())
                return
            }
        }
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (e: Exception) { Toast.makeText(this, "افتح إعدادات التطبيقات الافتراضية يدوياً", Toast.LENGTH_LONG).show() }
    }

    // ---------- أدوات ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun seekListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { onChange() }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
