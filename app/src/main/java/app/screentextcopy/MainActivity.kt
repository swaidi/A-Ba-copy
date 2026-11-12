package app.screentextcopy

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val scroll = findViewById<android.view.View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.version).text = "Version ${BuildConfig.VERSION_NAME}"

        findViewById<MaterialButton>(R.id.addShortcut).setOnClickListener {
            ShortcutEditor.showAdd(this) { renderShortcuts() }
        }
        renderShortcuts()
        status = findViewById(R.id.serviceStatus)
        val prefs = Prefs.get(this)

        findViewById<MaterialButton>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        bindSwitch(R.id.stripEnabled, Prefs.stripEnabled(this)) {
            prefs.edit().putBoolean(Prefs.KEY_STRIP_ENABLED, it).apply()
        }
        bindSwitch(R.id.stripVisible, Prefs.stripVisible(this)) {
            prefs.edit().putBoolean(Prefs.KEY_STRIP_VISIBLE, it).apply()
        }
        bindSwitch(R.id.arabic, Prefs.arabic(this)) {
            prefs.edit().putBoolean(Prefs.KEY_ARABIC, it).apply()
        }
        bindSwitch(R.id.toggleTile, Prefs.isComponentEnabled(this, ToggleTileService::class.java)) {
            Prefs.setComponentEnabled(this, ToggleTileService::class.java, it)
        }
        bindSwitch(R.id.captureTile, Prefs.isComponentEnabled(this, CaptureTileService::class.java)) {
            Prefs.setComponentEnabled(this, CaptureTileService::class.java, it)
        }

        bindSlider(R.id.height, R.id.heightLabel, "Strip height", Prefs.stripHeightDp(this), "dp") {
            prefs.edit().putInt(Prefs.KEY_STRIP_HEIGHT_DP, it).apply()
        }
        bindSlider(R.id.offset, R.id.offsetLabel, "Distance from bottom edge", Prefs.stripOffsetDp(this), "dp") {
            prefs.edit().putInt(Prefs.KEY_STRIP_OFFSET_DP, it).apply()
        }
        bindSlider(R.id.width, R.id.widthLabel, "Strip width", Prefs.stripWidthPct(this), "% of screen") {
            prefs.edit().putInt(Prefs.KEY_STRIP_WIDTH_PCT, it).apply()
        }

        findViewById<MaterialButton>(R.id.testCapture).setOnClickListener {
            val service = TriggerService.instance
            if (service != null) {
                moveTaskToBack(true)
                status.postDelayed({ service.captureNow() }, 500)
            } else {
                startActivity(Intent(this, ProjectionActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TriggerService.instance != null) {
            "On — long-press the bottom edge to copy text."
        } else {
            "Off — enable \"Alef_B_scan trigger\" in Accessibility settings."
        }
    }

    /** Lists saved shortcuts; the default one is starred. Tap a row for its options. */
    private fun renderShortcuts() {
        val box = findViewById<LinearLayout>(R.id.shortcutList)
        box.removeAllViews()
        val list = ShortcutStore.load(this)
        val def = ShortcutStore.defaultIndex(this, list.size)
        val density = resources.displayMetrics.density
        val ripple = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        if (list.isEmpty()) {
            box.addView(TextView(this).apply { text = "No shortcuts yet." })
        }
        list.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                setBackgroundResource(ripple)
                isClickable = true
                setOnClickListener { ShortcutEditor.showOptions(this@MainActivity, i) { renderShortcuts() } }
            }
            row.addView(TextView(this).apply {
                text = if (i == def) "★  ${s.name}" else s.name
                textSize = 16f
                setTypeface(typeface, if (i == def) Typeface.BOLD else Typeface.NORMAL)
            })
            row.addView(TextView(this).apply {
                text = s.describe()
                textSize = 13f
                alpha = 0.7f
            })
            box.addView(row)
        }
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun bindSlider(
        id: Int, labelId: Int, title: String, value: Int, unit: String, onChange: (Int) -> Unit
    ) {
        val bar = findViewById<SeekBar>(id)
        val label = findViewById<TextView>(labelId)
        bar.progress = value
        label.text = "$title: $value $unit"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = "$title: $progress $unit"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                onChange(seekBar.progress)
            }
        })
    }
}
