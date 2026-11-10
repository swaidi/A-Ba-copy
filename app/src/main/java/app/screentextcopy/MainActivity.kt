package app.screentextcopy

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
            "Off — enable \"Screen Text Copy trigger\" in Accessibility settings."
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
