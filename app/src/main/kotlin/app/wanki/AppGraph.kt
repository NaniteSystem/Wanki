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
import app.wanki.media.VoiceRecorder
import app.wanki.recognition.RecognizerPool
import app.wanki.recognition.Speaker
import app.wanki.recognition.LineRecognizer
import app.wanki.recognition.TextRecognition
import app.wanki.recognition.TranslationService
import app.wanki.refs.ReferenceRepository
import app.wanki.reminders.Reminders
import app.wanki.review.AnswerGrader
import app.wanki.texts.KuromojiMorphology
import app.wanki.texts.Morphology
import kotlinx.coroutines.CancellationException
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
    val texts = TextRepository(db, settings) { morphology }
    val grader = AnswerGrader(references, recognizers, settings)
    val textRecognition = TextRecognition(recognizers, settings)
    /** What reads a page's lines; tests swap in a fake. */
    @Volatile var lineRecognizer: LineRecognizer = textRecognition
    val translation = TranslationService(textRecognition)
    val speaker = Speaker(context)
    val backup = Backup(context, db, settings)
    val media = MediaStore(context)
    val recorder = VoiceRecorder(media)
    val morphology: Morphology by lazy { KuromojiMorphology() }
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
        graph.appScope.launch {
            if (!graph.settings.emptyInkCleaned) {
                runCatching { graph.repository.deleteEmptyInk() }.onSuccess { graph.settings.emptyInkCleaned = true }
            }
        }
        graph.appScope.launch {
            val today = java.time.LocalDate.now().toEpochDay()
            if (graph.settings.trashPurgedDay != today) {
                try {
                    graph.repository.purgeExpired()
                    graph.repository.collectMediaGarbage(graph.media)
                    graph.settings.trashPurgedDay = today
                    graph.settings.lastCleanupError = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("Wanki", "daily cleanup failed", e)
                    graph.settings.lastCleanupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
        graph.appScope.launch { runCatching { graph.backup.autoBackupIfDue() } }
    }
}

val Context.graph: AppGraph get() = (applicationContext as WankiApp).graph
