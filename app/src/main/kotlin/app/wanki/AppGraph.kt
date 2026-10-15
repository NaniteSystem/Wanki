package app.wanki

import android.app.Application
import android.content.Context
import app.wanki.data.Repository
import app.wanki.data.Settings
import app.wanki.data.WankiDatabase
import app.wanki.recognition.RecognizerPool
import app.wanki.refs.ReferenceRepository
import app.wanki.review.AnswerGrader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container: one instance per process. */
class AppGraph(context: Context) {
    val db = WankiDatabase.create(context)
    val settings = Settings(context)
    /** For work that must finish even when the screen that started it is gone (autosave, downloads). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val recognizers = RecognizerPool(appScope)
    val references = ReferenceRepository(context.assets)
    val repository = Repository(db)
    val grader = AnswerGrader(references, recognizers, settings)
}

class WankiApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as WankiApp).graph
