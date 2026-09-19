package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged

/** Programmatic settings UI backed by the lossless settings.json model. */
object LauncherSettingsDialog {
    fun show(
        context: Context,
        current: LauncherSettings,
        allowLanIntegration: Boolean = true,
        onPreview: (LauncherSettings) -> Unit = {},
        onSave: (LauncherSettings) -> Unit,
    ) {
        val session = LauncherSettingsEditorSession(current, onPreview)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
        }
        val textScale = slider(context, column, context.getString(R.string.setting_text_scale), 80, 140, (current.textScale * 100).toInt())
        val boxSpacing = slider(context, column, context.getString(R.string.setting_box_spacing), 0, 20, (current.boxSpacing * 10).toInt())
        val boxRadius = slider(context, column, context.getString(R.string.setting_box_radius), 0, 28, current.boxRadius.toInt())
        val boxBorderVisible = check(context, column, context.getString(R.string.setting_box_border_visible), current.boxBorderVisible)
        val boxBorderWidth = slider(context, column, context.getString(R.string.setting_box_border_width), 0, 8, current.boxBorderWidth.toInt())
        val boxIconSize = slider(context, column, context.getString(R.string.setting_box_icon_size), 36, 72, current.boxItemSize.toInt())
        val barRadius = slider(context, column, context.getString(R.string.setting_bar_radius), 0, 28, current.barRadius.toInt())
        val panelOpacity = slider(
            context,
            column,
            context.getString(R.string.setting_panel_transparency),
            50,
            100,
            (current.settingsPanelOpacity * 100).toInt(),
        )
        val gestureNavigation = check(context, column, context.getString(R.string.setting_gesture_navigation), current.gestureNavigationEnabled)
        val tapBoxes = check(context, column, context.getString(R.string.setting_show_touch_areas), current.showTapBoxes)
        val apiEnabled = check(context, column, context.getString(R.string.setting_local_api), current.apiServerEnabled).apply {
            visibility = if (allowLanIntegration) View.VISIBLE else View.GONE
        }
        val apiPort = input(context, column, context.getString(R.string.setting_api_port), current.apiServerPort.toString(), InputType.TYPE_CLASS_NUMBER).apply {
            visibility = if (allowLanIntegration) View.VISIBLE else View.GONE
        }
        val preferTermux = check(context, column, context.getString(R.string.setting_prefer_termux), current.shellPreferTermux)
        val quake = check(context, column, context.getString(R.string.setting_quake_terminal), current.quakeTerminal)
        val language = spinner(context, column, context.getString(R.string.setting_language), LauncherLanguage.entries.map { it.wireValue }, current.language.wireValue)
        column.addView(
            android.widget.Button(context).apply {
                text = context.getString(R.string.setting_set_wallpaper)
                setOnClickListener { TtfxWallpaper.enable(context) }
            },
        )
        val aiBase = input(context, column, context.getString(R.string.setting_ai_url), current.aiBaseUrl)
        val aiModel = input(context, column, context.getString(R.string.setting_ai_model), current.aiModel)
        val aiKey = input(
            context,
            column,
            context.getString(R.string.setting_ai_key),
            current.aiApiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val aiPrompt = input(context, column, context.getString(R.string.setting_ai_prompt), current.aiSystemPrompt)
        val scroll = ScrollView(context).apply { addView(column) }

        fun settingsFromControls() = current.copy(
            textScale = textScale.progress / 100.0,
            boxSpacing = boxSpacing.progress / 10.0,
            boxRadius = boxRadius.progress.toDouble(),
            boxBorderVisible = boxBorderVisible.isChecked,
            boxBorderWidth = boxBorderWidth.progress.toDouble(),
            boxItemSize = boxIconSize.progress.toDouble(),
            barRadius = barRadius.progress.toDouble(),
            settingsPanelOpacity = panelOpacity.progress / 100.0,
            gestureNavigationEnabled = gestureNavigation.isChecked,
            showTapBoxes = tapBoxes.isChecked,
            apiServerEnabled = allowLanIntegration && apiEnabled.isChecked,
            apiServerPort = apiPort.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: current.apiServerPort,
            shellPreferTermux = preferTermux.isChecked,
            quakeTerminal = quake.isChecked,
            language = LauncherLanguage.entries[language.selectedItemPosition],
            aiBaseUrl = aiBase.text.toString(),
            aiApiKey = aiKey.text.toString(),
            aiModel = aiModel.text.toString(),
            aiSystemPrompt = aiPrompt.text.toString(),
        )
        var dialog: AlertDialog? = null
        var ready = false
        fun updateLive() {
            if (!ready) return
            val value = settingsFromControls()
            session.update(value)
            dialog?.let { SettingsDialogSurface.apply(it, value.settingsPanelOpacity) }
        }
        listOf(textScale, boxSpacing, boxRadius, boxBorderWidth, boxIconSize, barRadius, panelOpacity).forEach { seek ->
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateLive()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        listOf(gestureNavigation, tapBoxes, apiEnabled, preferTermux, quake, boxBorderVisible)
            .forEach { it.setOnCheckedChangeListener { _, _ -> updateLive() } }
        listOf(language).forEach { spinner ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateLive()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        listOf(apiPort, aiBase, aiModel, aiKey, aiPrompt).forEach { it.doAfterTextChanged { updateLive() } }

        SettingsDialogSurface.constrainContent(context, scroll, current.settingsPanelOpacity)
        val createdDialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                session.update(settingsFromControls())
                session.commit(onSave)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = createdDialog
        createdDialog.setOnShowListener { SettingsDialogSurface.apply(createdDialog, settingsFromControls().settingsPanelOpacity) }
        createdDialog.setOnDismissListener { session.cancel() }
        ready = true
        createdDialog.show()
    }

    private fun check(context: Context, parent: LinearLayout, title: String, value: Boolean): CheckBox =
        CheckBox(context).apply {
            text = title
            isChecked = value
            parent.addView(this)
        }

    private fun slider(
        context: Context,
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        value: Int,
    ): SeekBar {
        parent.addView(TextView(context).apply { text = title })
        return SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun spinner(
        context: Context,
        parent: LinearLayout,
        title: String,
        values: List<String>,
        selected: String,
    ): Spinner {
        parent.addView(TextView(context).apply { text = title })
        return Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(values.indexOf(selected).coerceAtLeast(0))
            parent.addView(this)
        }
    }

    private fun input(
        context: Context,
        parent: LinearLayout,
        title: String,
        value: String,
        type: Int = InputType.TYPE_CLASS_TEXT,
    ): EditText {
        parent.addView(TextView(context).apply { text = title })
        return EditText(context).apply {
            setText(value)
            inputType = type
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
