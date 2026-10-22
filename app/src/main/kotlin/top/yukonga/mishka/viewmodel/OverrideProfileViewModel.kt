package top.yukonga.mishka.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.ImportError
import top.yukonga.mishka.data.repository.OverrideInputError
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.domain.repository.OverrideProfileRepository
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.util.describe

@Immutable
data class OverrideProfileUiState(
    val profiles: ImmutableList<OverrideProfile> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String = "",
)

class OverrideProfileViewModel(
    private val repository: OverrideProfileRepository,
    private val subscriptions: SubscriptionRepository,
    private val serviceController: ProxyServiceController,
    private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverrideProfileUiState(profiles = repository.profiles.value))
    val uiState: StateFlow<OverrideProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.profiles.collect { list -> _uiState.update { it.copy(profiles = list) } } }
    }

    fun clearError() = _uiState.update { it.copy(error = "") }
    suspend fun readContent(id: String): String = repository.readContent(id)

    fun addRemote(name: String, url: String, format: OverrideFormat, onComplete: () -> Unit) =
        launchOperation(R.string.error_import_failed) { repository.addRemote(name, url, format); onComplete() }

    fun addLocal(name: String, fileName: String, format: OverrideFormat, content: String, onComplete: () -> Unit) =
        launchOperation(R.string.error_import_failed) { repository.addLocal(name, fileName, format, content); onComplete() }

    fun addBlank(name: String, format: OverrideFormat, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) { repository.addBlank(name, format); onComplete() }

    fun edit(profile: OverrideProfile, name: String, url: String, format: OverrideFormat, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) {
            repository.edit(profile.id, name, url, format)
            if (format != profile.format) restartIfUsed(profile.id)
            onComplete()
        }

    fun saveContent(id: String, content: String, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) { repository.saveContent(id, content); restartIfUsed(id); onComplete() }

    fun update(id: String) = launchOperation(R.string.error_update_failed) { repository.update(id); restartIfUsed(id) }

    fun updateAll() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            var succeeded = 0
            val failures = mutableListOf<String>()
            var restart = false
            try {
                _uiState.value.profiles.filter { it.isRemote }.forEach { profile ->
                    try {
                        repository.update(profile.id)
                        succeeded++
                        if (repository.isUsedByCurrent(profile.id)) restart = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures += "${profile.name}: ${localizedMessage(e)}"
                    }
                }
                if (restart) restartCurrent()
                showToast(context.getString(R.string.override_update_all_result, succeeded, failures.size))
                _uiState.update { it.copy(error = failures.joinToString("\n")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(error = context.getString(R.string.error_update_failed, localizedMessage(e))) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun delete(id: String) = launchOperation(R.string.error_save_failed) {
        val used = repository.isUsedByCurrent(id)
        repository.delete(id)
        if (used) restartCurrent()
    }

    fun setSelection(subscriptionId: String, overrideIds: List<String>, sortPreference: List<String>, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) {
            val subscription = subscriptions.subscriptions.value.find { it.id == subscriptionId }
            repository.setSelection(subscriptionId, overrideIds, sortPreference)
            val changed = subscription?.orderedOverrideIds != overrideIds
            if (changed && subscriptions.getActiveId() == subscriptionId) serviceController.restartWhenReady(subscriptionId)
            onComplete()
        }

    private suspend fun restartIfUsed(id: String) {
        if (repository.isUsedByCurrent(id)) restartCurrent()
    }

    private fun restartCurrent() {
        subscriptions.getActiveId()?.let(serviceController::restartWhenReady)
    }

    private fun launchOperation(@StringRes errorKey: Int, block: suspend () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val error = try {
                block(); ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is ConfigValidationException) context.getString(R.string.error_validation_failed, e.describe())
                else context.getString(errorKey, localizedMessage(e))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
            _uiState.update { it.copy(error = error) }
        }
    }

    private fun localizedMessage(e: Throwable): String = when (e) {
        is OverrideInputError.NameRequired -> context.getString(R.string.override_error_name_required)
        is OverrideInputError.InvalidUrl -> context.getString(R.string.override_error_invalid_url)
        is ImportError.HttpStatus -> context.getString(R.string.override_error_http_status, e.code)
        else -> e.describe()
    }
}
