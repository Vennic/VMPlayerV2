package com.kuzheevadel.vmplayerv2.viewmodels

import android.annotation.SuppressLint
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.util.Log
import com.kuzheevadel.vmplayerv2.common.State
import com.kuzheevadel.vmplayerv2.database.PlaylistDatabase
import com.kuzheevadel.vmplayerv2.interfaces.Interfaces
import com.kuzheevadel.vmplayerv2.model.Track
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.Callable
import javax.inject.Inject

class PlaylistViewModel @Inject constructor(database: PlaylistDatabase,
                                            private val mediaRepository: Interfaces.StorageMediaRepository): ViewModel() {

    private val tracksDao = database.trackDao()
    val trackData: MutableLiveData<MutableList<Track>> = MutableLiveData()
    val loadStatus: MutableLiveData<State> = MutableLiveData()
    private val disposable = CompositeDisposable()
    private var isLoadedBefore = false

    @SuppressLint("CheckResult")
    fun loadPlaylistFromDatabase() {
        val callable = Callable<MutableList<Track>> {
            val playlist = tracksDao.getAllTracks()
            mediaRepository.comparePlaylistWithUploaded(playlist)
        }
        loadStatus.value = State.LOADING

        disposable.add(
            Observable.fromCallable(callable)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                if (!isLoadedBefore) {
                    refreshPlaylistDatabase(it)
                    isLoadedBefore = true
                }

                Log.i("MOVETEST", "Playlist: $it")

                trackData.value = it
                loadStatus.value = State.DONE
            },
                {

                    Log.e("PLAYLISTERROR", "", it)
                    loadStatus.value = State.ERROR
                }))
    }

    @SuppressLint("CheckResult")
    private fun refreshPlaylistDatabase(list: MutableList<Track>) {
        val callable = Callable<MutableList<Track>>{
            val currentList = tracksDao.getAllTracks()
            currentList.removeAll(list)
            currentList
        }

        Observable.fromCallable(callable)
            .subscribeOn(Schedulers.io())
            .flatMap { Observable.fromIterable(it) }
            .subscribe(
                {tracksDao.deleteTrack(it)},
                {Log.e("DataDeleteError", "can't delete track", it)}
            )
    }

    override fun onCleared() {
        disposable.dispose()
        super.onCleared()
    }
}