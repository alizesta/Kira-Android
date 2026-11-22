package ai.kira.mobile

import android.app.Application
import ai.kira.mobile.application.KiraContainer

/** Application-scoped composition root. */
class KiraMobileApplication : Application() {
    val container: KiraContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KiraContainer(applicationContext)
    }
}
