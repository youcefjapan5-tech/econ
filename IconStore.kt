package app.videowall

import android.content.Context
import org.json.JSONObject
import java.io.File

/** يحفظ تخصيص كل أيقونة (الشكل/الاستدارة/الأبعاد/صورة مخصصة) والإعداد العام الافتراضي. */
class IconStore(private val ctx: Context) {

    class Cfg(
        var shape: IconShape.Shape = IconShape.Shape.ROUNDED,
        var radius: Float = 0.28f,   // نسبة الاستدارة 0..0.5
        var sizeDp: Int = 64,        // أبعاد الأيقونة بالـdp
        var image: String? = null    // مسار الصورة المخصصة إن وُجدت
    ) {
        fun copyNoImage() = Cfg(shape, radius, sizeDp, null)
        fun toJson() = JSONObject().apply {
            put("shape", shape.name)
            put("radius", radius.toDouble())
            put("sizeDp", sizeDp)
            if (image != null) put("image", image)
        }
    }

    companion object {
        private const val PREF = "icons"
        const val GLOBAL = "__global__"
    }

    private val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(key: String): Cfg? {
        val s = sp.getString(key, null) ?: return null
        return try {
            val o = JSONObject(s)
            Cfg(
                shape = runCatching { IconShape.Shape.valueOf(o.getString("shape")) }.getOrDefault(IconShape.Shape.ROUNDED),
                radius = o.optDouble("radius", 0.28).toFloat(),
                sizeDp = o.optInt("sizeDp", 64),
                image = o.optString("image", "").ifEmpty { null }
            )
        } catch (e: Exception) { null }
    }

    private fun write(key: String, cfg: Cfg) =
        sp.edit().putString(key, cfg.toJson().toString()).apply()

    fun global(): Cfg = read(GLOBAL) ?: Cfg()

    fun setGlobal(cfg: Cfg) = write(GLOBAL, cfg)

    /** الإعداد الفعّال لتطبيق معيّن: تخصيصه إن وُجد، وإلا يرث الشكل العام بلا صورة. */
    fun forPkg(pkg: String): Cfg = read(pkg) ?: global().copyNoImage()

    fun setPkg(pkg: String, cfg: Cfg) = write(pkg, cfg)

    fun hasCustom(pkg: String): Boolean = read(pkg) != null

    fun clearPkg(pkg: String) = sp.edit().remove(pkg).apply()

    fun allPackages(): Set<String> = sp.all.keys.filter { it != GLOBAL }.toSet()

    /** يطبّق الشكل/الاستدارة/الأبعاد على كل التطبيقات مع الاحتفاظ بصورها المخصصة. */
    fun applyShapeToAll(shape: IconShape.Shape, radius: Float, sizeDp: Int) {
        val g = global()
        g.shape = shape; g.radius = radius; g.sizeDp = sizeDp
        setGlobal(g)
        val e = sp.edit()
        for (pkg in allPackages()) {
            val c = read(pkg) ?: continue
            c.shape = shape; c.radius = radius; c.sizeDp = sizeDp
            e.putString(pkg, c.toJson().toString())
        }
        e.apply()
    }

    // ---- الصور المخصصة ----
    fun iconsDir(): File = File(ctx.filesDir, "icons").apply { if (!exists()) mkdirs() }

    fun imageFile(pkg: String): File = File(iconsDir(), "$pkg.png")

    fun deleteImage(pkg: String) {
        imageFile(pkg).takeIf { it.exists() }?.delete()
        val c = read(pkg) ?: return
        c.image = null
        write(pkg, c)
    }
}
