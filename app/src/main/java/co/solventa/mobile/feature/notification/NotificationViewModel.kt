package co.solventa.mobile.feature.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal enum class NotificationMessage { PERMISSION_DENIED, RESOURCE_UNAVAILABLE }

internal sealed interface NotificationNavigation {
    data class Claim(val id: String) : NotificationNavigation
}

internal data class NotificationUiState(
    val actor: String? = null,
    val open: Boolean = false,
    val busy: Boolean = false,
    val loadingNotifications: Boolean = false,
    val items: List<NotificationItem> = emptyList(),
    val permissionGranted: Boolean = false,
    val pushEnabled: Boolean = false,
    val pushPreferencesKnown: Boolean = false,
    val error: NotificationError? = null,
    val message: NotificationMessage? = null,
    val navigation: NotificationNavigation? = null,
    val undo: NotificationUndo? = null,
    val removedItem: NotificationItem? = null,
)

@HiltViewModel
internal class NotificationViewModel @Inject constructor(
    private val api: NotificationApi,
    private val store: NotificationLocalStore,
    private val presenter: NotificationPresenter,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        NotificationUiState(permissionGranted = presenter.permissionGranted()),
    )
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var pendingTarget: NotificationTarget? = null

    fun bindActor(actor: String?) {
        if (actor == state.value.actor) return
        generation++
        job?.cancel()
        if (state.value.actor != null) {
            pendingTarget = null
            presenter.clear()
        }
        mutable.value = NotificationUiState(
            actor = actor,
            permissionGranted = presenter.permissionGranted(),
        )
        if (actor != null) synchronize(actor)
    }

    fun show(actor: String) {
        if (actor != state.value.actor) bindActor(actor)
        mutable.value = state.value.copy(
            open = true, message = null, permissionGranted = presenter.permissionGranted(),
        )
        synchronize(actor)
    }

    fun close() {
        generation++
        job?.cancel()
        mutable.value = NotificationUiState(
            actor = state.value.actor,
            permissionGranted = presenter.permissionGranted(),
        )
    }

    fun synchronize(actor: String) {
        if (actor != state.value.actor || state.value.busy) return
        val version = generation
        mutable.value = state.value.copy(busy = true, loadingNotifications = true, error = null)
        job = viewModelScope.launch {
            try {
                val notifications = api.notifications(actor).sortedByDescending { it.deliveredAt }
                val preferences = api.preferences(actor)
                coroutineContext.ensureActive()
                if (version != generation || actor != state.value.actor) return@launch
                val pushByKind = preferences
                    .filter { it.channel == NotificationChannel.PUSH }
                    .associate { it.kind to it.enabled }
                val permission = presenter.permissionGranted()
                val discarded = store.discarded(actor)
                synchronized(store) {
                    val fresh = store.recordSeen(actor, notifications.mapTo(mutableSetOf()) { it.id })
                    if (permission) {
                        notifications
                            .filter { it.id in fresh && it.id !in discarded && pushByKind[it.kind] == true }
                            .forEach(presenter::show)
                    }
                }
                val read = store.read(actor)
                update(version) {
                    copy(
                        busy = false,
                        loadingNotifications = false,
                        items = notifications.filterNot { it.id in discarded }.map { NotificationItem(it, it.id in read) },
                        permissionGranted = permission,
                        pushEnabled = NotificationKind.entries.all { pushByKind[it] == true },
                        pushPreferencesKnown = true,
                        error = null,
                    )
                }
                resolvePending(version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: NotificationProblem) {
                update(version) { copy(busy = false, loadingNotifications = false, error = problem.reason) }
            } catch (_: SecurityException) {
                update(version) {
                    copy(busy = false, loadingNotifications = false, permissionGranted = false,
                        message = NotificationMessage.PERMISSION_DENIED)
                }
            } catch (_: Exception) {
                update(version) { copy(busy = false, loadingNotifications = false, error = NotificationError.STORAGE) }
            }
        }
    }

    fun refreshPermission() {
        val granted = presenter.permissionGranted()
        mutable.value = state.value.copy(
            permissionGranted = granted,
            message = if (granted && state.value.message == NotificationMessage.PERMISSION_DENIED) null
                else state.value.message,
        )
    }

    fun permissionResult(granted: Boolean) {
        mutable.value = state.value.copy(
            permissionGranted = presenter.permissionGranted(),
            message = if (granted) null else NotificationMessage.PERMISSION_DENIED,
        )
        setPush(granted)
    }

    fun setPush(enabled: Boolean) {
        val actor = state.value.actor ?: return
        if (enabled && !presenter.permissionGranted()) {
            mutable.value = state.value.copy(
                permissionGranted = false,
                message = NotificationMessage.PERMISSION_DENIED,
            )
            return
        }
        generation++
        job?.cancel()
        val version = generation
        mutable.value = state.value.copy(
            busy = true,
            loadingNotifications = false,
            error = null,
            message = if (!enabled && !state.value.permissionGranted) {
                state.value.message
            } else {
                null
            },
        )
        job = viewModelScope.launch {
            try {
                val preferences = api.updatePush(actor, enabled)
                val pushByKind = preferences
                    .filter { it.channel == NotificationChannel.PUSH }
                    .associate { it.kind to it.enabled }
                update(version) {
                    copy(
                        busy = false,
                        permissionGranted = presenter.permissionGranted(),
                        pushEnabled = NotificationKind.entries.all { pushByKind[it] == true },
                        pushPreferencesKnown = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: NotificationProblem) {
                update(version) { copy(busy = false, error = problem.reason) }
            } catch (_: Exception) {
                update(version) { copy(busy = false, error = NotificationError.NETWORK) }
            }
        }
    }

    fun markRead(item: NotificationItem) = change(item, discard = false)

    fun discard(item: NotificationItem) = change(item, discard = true)

    private fun change(item: NotificationItem, discard: Boolean) {
        val actor = state.value.actor ?: return
        if (item !in state.value.items || (!discard && item.read)) return
        try {
            val undo = if (discard) store.change(actor, item.notification.id, discarded = true)
                else store.change(actor, item.notification.id, read = true)
            mutable.value = state.value.copy(
                items = if (discard) state.value.items - item else state.value.items.map {
                    if (it.notification.id == item.notification.id) it.copy(read = true) else it
                },
                undo = undo,
                removedItem = if (discard) item else null,
                error = null,
            )
        } catch (problem: NotificationProblem) {
            mutable.value = state.value.copy(error = problem.reason)
        }
    }

    fun undo(change: NotificationUndo) {
        val actor = state.value.actor ?: return
        if (change != state.value.undo) return
        try {
            if (store.undo(actor, change)) {
                val restored = state.value.removedItem
                mutable.value = state.value.copy(
                    items = (state.value.items + listOfNotNull(restored)).map {
                        if (it.notification.id == change.id) it.copy(read = change.read) else it
                    }.distinctBy { it.notification.id }.sortedByDescending { it.notification.deliveredAt },
                )
            }
            clearUndo(change)
        } catch (problem: NotificationProblem) {
            mutable.value = state.value.copy(error = problem.reason)
        }
    }

    fun clearUndo(change: NotificationUndo) {
        if (state.value.undo == change) mutable.value = state.value.copy(undo = null, removedItem = null)
    }

    fun markAllRead() {
        val actor = state.value.actor ?: return
        val ids = state.value.items.mapTo(mutableSetOf()) { it.notification.id }
        try {
            store.markAllRead(actor, ids)
            mutable.value = state.value.copy(items = state.value.items.map { it.copy(read = true) })
        } catch (problem: NotificationProblem) {
            mutable.value = state.value.copy(error = problem.reason)
        }
    }

    fun open(item: NotificationItem) {
        if (item !in state.value.items) return
        authorizeAndOpen(NotificationTarget(item.notification.id, item.notification.resource))
    }

    fun acceptTarget(notificationId: String?, resourceKind: String?, resourceId: String?) {
        val target = try {
            NotificationTarget(
                uuid(requireNotNull(notificationId)),
                NotificationResource(
                    NotificationResourceKind.from(requireNotNull(resourceKind)),
                    uuid(requireNotNull(resourceId)),
                ),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        pendingTarget = target
        state.value.actor?.let(::synchronize)
    }

    fun consumeNavigation() {
        mutable.value = state.value.copy(navigation = null)
    }

    private fun resolvePending(version: Long) {
        if (version != generation) return
        val target = pendingTarget ?: return
        val authorized = state.value.items.singleOrNull {
            it.notification.id == target.notificationId &&
                it.notification.resource == target.resource
        }
        pendingTarget = null
        if (authorized == null) {
            mutable.value = state.value.copy(
                open = true,
                message = NotificationMessage.RESOURCE_UNAVAILABLE,
            )
        } else {
            authorizeAndOpen(target)
        }
    }

    private fun authorizeAndOpen(target: NotificationTarget) {
        val actor = state.value.actor ?: run {
            pendingTarget = target
            return
        }
        val authorized = state.value.items.singleOrNull {
            it.notification.id == target.notificationId &&
                it.notification.resource == target.resource
        } ?: run {
            mutable.value = state.value.copy(
                open = true,
                message = NotificationMessage.RESOURCE_UNAVAILABLE,
            )
            return
        }
        try {
            store.markRead(actor, authorized.notification.id)
            mutable.value = state.value.copy(
                open = state.value.open || target.resource.kind != NotificationResourceKind.CLAIM,
                items = state.value.items.map {
                    if (it.notification.id == authorized.notification.id) it.copy(read = true) else it
                },
                navigation = when (target.resource.kind) {
                    NotificationResourceKind.CLAIM -> NotificationNavigation.Claim(target.resource.id)
                    NotificationResourceKind.PAYMENT,
                    NotificationResourceKind.POLICY,
                    -> null
                },
                message = if (target.resource.kind == NotificationResourceKind.CLAIM) {
                    null
                } else {
                    NotificationMessage.RESOURCE_UNAVAILABLE
                },
            )
        } catch (problem: NotificationProblem) {
            mutable.value = state.value.copy(error = problem.reason)
        }
    }

    private fun update(version: Long, change: NotificationUiState.() -> NotificationUiState) {
        if (version == generation) mutable.value = state.value.change()
    }

    private fun uuid(value: String): String =
        UUID.fromString(value).toString().also { require(it == value) }
}
