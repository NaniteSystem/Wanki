package app.wanki

import android.app.Application
import android.content.Context
import app.wanki.catalog.Catalog
import app.wanki.catalog.CatalogInstaller
import app.wanki.data.Backup
import app.wanki.data.Repository
import app.wanki.data.Settings
import app.wanki.data.TextRepository
import app.wanki.data.WankiDatabase
import app.wanki.media.MediaStore
import app.wanki.recognition.RecognizerPool
import app.wanki.recognition.Speaker
import app.wanki.recognition.TextRecognition
import app.wanki.recognition.TranslationService
import app.wanki.refs.ReferenceRepository
import app.wanki.reminders.Reminders
import app.wanki.review.AnswerGrader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container: one instance per process. */
class AppGraph(context: Context) {
    val db = WankiDatabase.create(context)
    val settings = Settings(context)
    /** For work that must finish even when the screen that started it is gone (autosave, downloads). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val recognizers = RecognizerPool(appScope)
    val references = ReferenceRepository(context.assets)
    val repository = Repository(db)
    val texts = TextRepository(db, settings)
    val grader = AnswerGrader(references, recognizers, settings)
    val textRecognition = TextRecognition(recognizers, settings)
    val translation = TranslationService(textRecognition)
    val speaker = Speaker(context)
    val backup = Backup(context, db, settings)
    val media = MediaStore(context)
    val catalog = Catalog(context.assets)
    val catalogInstaller = CatalogInstaller(catalog, repository, appScope)
}

class WankiApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Reminders.schedule(this)
        graph.appScope.launch { runCatching { graph.backup.autoBackupIfDue() } }
    }
}

val Context.graph: AppGraph get() = (applicationContext as WankiApp).graph
