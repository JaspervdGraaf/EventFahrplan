package nerd.tuxmobil.fahrplan.congress.favorites

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import info.metadude.android.eventfahrplan.commons.logging.Logging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import nerd.tuxmobil.fahrplan.congress.exceptions.ExceptionHandling
import nerd.tuxmobil.fahrplan.congress.models.Session
import nerd.tuxmobil.fahrplan.congress.repositories.AppRepository
import nerd.tuxmobil.fahrplan.congress.sharing.JsonSessionFormat
import nerd.tuxmobil.fahrplan.congress.sharing.SimpleSessionFormat

class StarredListViewModel(

    private val repository: AppRepository,
    databaseDispatcher: CoroutineDispatcher,
    exceptionHandling: ExceptionHandling,
    private val logging: Logging,
    private val simpleSessionFormat: SimpleSessionFormat,
    private val jsonSessionFormat: JsonSessionFormat

) : ViewModel() {

    private companion object {
        const val LOG_TAG = "StarredListViewModel"
    }

    private val databaseJob = Job()
    private val databaseScope = CoroutineScope(
        databaseDispatcher +
                databaseJob +
                CoroutineExceptionHandler(exceptionHandling::onExceptionHandling)
    )

    private val mutableStarredListParameter = MutableLiveData<StarredListParameter>()
    val starredListParameter: LiveData<StarredListParameter> = mutableStarredListParameter

    private val mutableShareSimple = MutableLiveData<String>()
    val shareSimple: LiveData<String> = mutableShareSimple

    private val mutableShareJson = MutableLiveData<String>()
    val shareJson: LiveData<String> = mutableShareJson

    init {
        updateStarredListParameter()
    }

    private fun updateStarredListParameter() {
        databaseScope.launch {
            repository.starredSessions.collect { sessions ->
                val numDays = if (sessions.isEmpty()) 0 else repository.readMeta().numDays
                val useDeviceTimeZone = if (sessions.isEmpty()) false else repository.readUseDeviceTimeZoneEnabled()
                val parameter = StarredListParameter(sessions, numDays, useDeviceTimeZone)
                logging.d(LOG_TAG, "Loaded ${sessions.size} starred sessions.")
                mutableStarredListParameter.postValue(parameter)
            }
        }
    }

    override fun onCleared() {
        databaseJob.cancel()
        super.onCleared()
    }

    fun delete(session: Session) {
        databaseScope.launch {
            repository.updateHighlight(session)
            repository.notifyHighlightsChanged() // TODO Remove when FahrplanFragment uses Flow
        }
    }

    fun deleteAll() {
        databaseScope.launch {
            repository.deleteAllHighlights()
            repository.notifyHighlightsChanged() // TODO Remove when FahrplanFragment uses Flow
        }
    }

    fun share(sessions: List<Session>) {
        databaseScope.launch {
            val timeZoneId = repository.readMeta().timeZoneId
            simpleSessionFormat.format(sessions, timeZoneId)?.let { formattedSession ->
                mutableShareSimple.postValue(formattedSession)
            }
        }
    }

    fun shareToChaosflix(sessions: List<Session>) {
        jsonSessionFormat.format(sessions)?.let { formattedSession ->
            mutableShareJson.postValue(formattedSession)
        }
    }

}
