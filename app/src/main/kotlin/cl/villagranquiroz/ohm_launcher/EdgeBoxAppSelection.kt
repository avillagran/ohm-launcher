package cl.villagranquiroz.ohm_launcher

class EdgeBoxAppSelection(private val apps: List<InstalledApp>) {
    private val selectedKeys = linkedSetOf<String>()

    fun toggle(app: InstalledApp) {
        val key = app.key()
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
    }

    fun isSelected(app: InstalledApp): Boolean = app.key() in selectedKeys

    fun selectedApps(): List<InstalledApp> = apps.filter(::isSelected)

    private fun InstalledApp.key(): String = "$packageName/$activityName"
}