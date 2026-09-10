package android.content
import android.app.NotificationManager
import android.os.PowerManager
open class Context {
    val packageName = "test"
    val filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "listening-service-test")
    fun getSystemService(name: String): Any = when (name) {
        NOTIFICATION_SERVICE -> NotificationManager
        POWER_SERVICE -> PowerManager()
        else -> error(name)
    }
    fun getString(id: Int) = "Listening"
    fun startService(intent: Intent) = Unit
    companion object {
        const val NOTIFICATION_SERVICE = "notification"
        const val POWER_SERVICE = "power"
    }
}
class Intent {
    var action: String? = null
        private set
    constructor(action: String) { this.action = action }
    constructor(context: Context, clazz: Class<*>)
    fun setAction(value: String): Intent { action = value; return this }
    fun putExtra(name: String, value: Any) = this
}
