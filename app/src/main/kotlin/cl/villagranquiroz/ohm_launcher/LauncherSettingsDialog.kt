package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Programmatic settings UI backed by the lossless settings.json model. */
object LauncherSettingsDialog {
    fun show(context: Context, current: LauncherSettings, onSave: (LauncherSettings) -> Unit) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
        }
        val textScale = slider(context, column, "Escala de texto", 80, 140, (current.textScale * 100).toInt())
        val boxSpacing = slider(context, column, "Espacio entre cajas", 0, 20, (current.boxSpacing * 10).toInt())
        val boxRadius = slider(context, column, "Radio de cajas", 0, 28, current.boxRadius.toInt())
        val barRadius = slider(context, column, "Radio de barras", 0, 28, current.barRadius.toInt())
        val favoritesVisible = check(context, column, "Mostrar favoritos", current.favoritesBarVisible)
        val favoritePosition = spinner(context, column, "Posición de favoritos", LauncherEdge.entries.map { it.wireValue }, current.favoritesBarPosition.wireValue)
        val modeValues = listOf("auto") + FavoritesBarMode.entries.map { it.wireValue }
        val favoriteMode = spinner(context, column, "Diseño de favoritos", modeValues, current.favoritesBarMode?.wireValue ?: "auto")
        val bottomVisible = check(context, column, "Mostrar barra de comandos", current.bottomBarVisible)
        val bottomPosition = spinner(context, column, "Posición de comandos", LauncherEdge.entries.map { it.wireValue }, current.bottomBarPosition.wireValue)
        val gestureNavigation = check(context, column, "Navegación por gestos", current.gestureNavigationEnabled)
        val tapBoxes = check(context, column, "Mostrar áreas táctiles", current.showTapBoxes)
        val apiEnabled = check(context, column, "API local habilitada", current.apiServerEnabled)
        val apiPort = input(context, column, "Puerto API", current.apiServerPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val preferTermux = check(context, column, "Preferir Termux", current.shellPreferTermux)
        val quake = check(context, column, "Terminal Quake", current.quakeTerminal)
        val language = spinner(context, column, "Idioma", LauncherLanguage.entries.map { it.wireValue }, current.language.wireValue)
        val aiBase = input(context, column, "URL de IA", current.aiBaseUrl)
        val aiModel = input(context, column, "Modelo de IA", current.aiModel)
        val aiKey = input(
            context,
            column,
            "Clave API de IA",
            current.aiApiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val aiPrompt = input(context, column, "Prompt del sistema", current.aiSystemPrompt)
        val scroll = ScrollView(context).apply { addView(column) }

        AlertDialog.Builder(context)
            .setTitle("Configuración de Ohm Launcher")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(
                    current.copy(
                        textScale = textScale.progress / 100.0,
                        boxSpacing = boxSpacing.progress / 10.0,
                        boxRadius = boxRadius.progress.toDouble(),
                        barRadius = barRadius.progress.toDouble(),
                        favoritesBarVisible = favoritesVisible.isChecked,
                        favoritesBarPosition = LauncherEdge.entries[favoritePosition.selectedItemPosition],
                        favoritesBarMode = favoriteMode.selectedItemPosition.takeIf { it > 0 }
                            ?.let { FavoritesBarMode.entries[it - 1] },
                        bottomBarVisible = bottomVisible.isChecked,
                        bottomBarPosition = LauncherEdge.entries[bottomPosition.selectedItemPosition],
                        gestureNavigationEnabled = gestureNavigation.isChecked,
                        showTapBoxes = tapBoxes.isChecked,
                        apiServerEnabled = apiEnabled.isChecked,
                        apiServerPort = apiPort.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: current.apiServerPort,
                        shellPreferTermux = preferTermux.isChecked,
                        quakeTerminal = quake.isChecked,
                        language = LauncherLanguage.entries[language.selectedItemPosition],
                        aiBaseUrl = aiBase.text.toString(),
                        aiApiKey = aiKey.text.toString(),
                        aiModel = aiModel.text.toString(),
                        aiSystemPrompt = aiPrompt.text.toString(),
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
