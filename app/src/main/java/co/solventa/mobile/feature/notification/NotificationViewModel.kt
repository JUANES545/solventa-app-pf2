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

internal enum class NotificationMessage { PERMISSION_DENIED, RESOURCE_UNAVAILABLE }

internal sealed interface NotificationNavigation {
    data class Claim(val id: String) : NotificationNavigation
}

internal data class NotificationUiState(
    val actor: String? = null,
    val open: Boolean = false,
    val busy: Boolean = false,
    val items: List<NotificationItem> = emptyList(),
    val permissionGranted: Boolean = false,
    val pushEnabled: Boolean = false,
    val error: NotificationError? = null,
    val message: NotificationMessage? = null,
    val navigation: NotificationNavigation? = null,
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
        mutable.value = NotificationUiState(
            actor = actor,
            permissionGranted = presenter.permissionGranted(),
        )
        if (actor != null) synchronize(actor)
    }

    fun show(actor: String) {
        if (actor != state.value.actor) bindActor(actor)
        mutable.value = state.value.copy(open = true, message = null)
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
        mutable.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                val notifications = api.notifications(actor).sortedByDescending { it.deliveredAt }
                val preferences = api.preferences(actor)
                val pushByKind = preferences
                    .filter { it.channel == NotificationChannel.PUSH }
                    .associate { it.kind to it.enabled }
                val permission = presenter.permissionGranted()
                val fresh = store.recordSeen(actor, notifications.mapTo(mutableSetOf()) { it.id })
                if (permission) {
                    notifications
                        .filter { it.id in fresh && pushByKind[it.kind] == true }
                        .forEach(presenter::show)
                }
                val read = store.read(actor)
                update(version) {
                    copy(
                        busy = false,
                        items = notifications.map { NotificationItem(it, it.id in read) },
                        permissionGranted = permission,
                        pushEnabled = NotificationKind.entries.all { pushByKind[it] == true },
                        error = null,
                    )
                }
                resolvePending(version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: NotificationProblem) {
                update(version) { copy(busy = false, error = problem.reason) }
            } catch (_: SecurityException) {
                update(version) {
                    copy(
                        busy = false,
                        permissionGranted = false,
                        message = NotificationMessage.PERMISSION_DENIED,
                    )
                }
            }
        }
    }

    fun permissionResult(granted: Boolean) {
        mutable.value = state.value.copy(
            permissionGranted = granted,
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
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: NotificationProblem) {
                update(version) { copy(busy = false, error = problem.reason) }
            }
        }
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
