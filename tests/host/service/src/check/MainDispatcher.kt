package check
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
private class TestMainDispatcher : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher get() = this
    override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() != Looper.getMainLooper()
    override fun dispatch(context: CoroutineContext, block: Runnable) { Handler(Looper.getMainLooper()).post(block) }
}
@OptIn(InternalCoroutinesApi::class)
class TestMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority = Int.MAX_VALUE
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = TestMainDispatcher()
    override fun hintOnError(): String? = null
}
