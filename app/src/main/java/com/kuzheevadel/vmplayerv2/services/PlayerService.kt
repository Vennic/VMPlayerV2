package com.kuzheevadel.vmplayerv2.services

import android.app.*
import android.arch.lifecycle.MutableLiveData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import com.kuzheevadel.vmplayerv2.R
import com.kuzheevadel.vmplayerv2.common.Constants
import com.kuzheevadel.vmplayerv2.common.ShowPanelMessage
import com.kuzheevadel.vmplayerv2.common.Source
import com.kuzheevadel.vmplayerv2.common.UpdateUIMessage
import com.kuzheevadel.vmplayerv2.dagger.App
import com.kuzheevadel.vmplayerv2.helper.PlayerStyleHelper
import com.kuzheevadel.vmplayerv2.interfaces.Interfaces
import com.kuzheevadel.vmplayerv2.model.RadioStation
import com.kuzheevadel.vmplayerv2.model.Track
import com.kuzheevadel.vmplayerv2.repository.RadioRepository
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PlayerService: Service() {

    @Inject
    lateinit var mediaRepository: Interfaces.StorageMediaRepository

    @Inject
    lateinit var radioRepository: RadioRepository

    private lateinit var disposable: CompositeDisposable
    private lateinit var subscription: Disposable
    val progressData: MutableLiveData<Int> = MutableLiveData()
    private var source = Source.TRACK
    private var currentState = PlaybackStateCompat.STATE_STOPPED

    private lateinit var mExoPlayer: SimpleExoPlayer
    private val metadataBuilder: MediaMetadataCompat.Builder = MediaMetadataCompat.Builder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var isAudioFocusRequest = false
    private lateinit var audioManager: AudioManager
    private lateinit var dataSourceFactory: CacheDataSourceFactory
    private lateinit var extractorsFactory: DefaultExtractorsFactory
    private var currentPlayingTrackId: Long = -1
    private val mHandler = Handler()

    private val delayedRunnable = Runnable { mediaSessionCallback.onStop() }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY === intent?.action) {
                mediaSessionCallback.onPause()
            }
        }
    }

    private val stateBuilder = PlaybackStateCompat.Builder().setActions(
        PlaybackStateCompat.ACTION_PLAY
                or PlaybackStateCompat.ACTION_STOP
                or PlaybackStateCompat.ACTION_PAUSE
                or PlaybackStateCompat.ACTION_PLAY_PAUSE
                or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    )

    private val target = object : Target {
        override fun onPrepareLoad(placeHolderDrawable: Drawable?) {
        }

        override fun onBitmapFailed(e: java.lang.Exception?, errorDrawable: Drawable?) {
        }

        override fun onBitmapLoaded(bitmap: Bitmap?, from: Picasso.LoadedFrom?) {
            mediaSession.setMetadata(setRadioMediaMetaData(radioRepository.currentPlayingStation!!, bitmap))
            refreshNotification(currentState)
        }
    }

    override fun onCreate() {
        super.onCreate()

        (application as App).getComponent().inject(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val notificationChannel = NotificationChannel(Constants.NOTIFICATION_DEFAULT_CHANNEL, "vmplayer", NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)


            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setAudioAttributes(audioAttributes)
                .build()
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "VMPlayer")

        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, applicationContext, MediaButtonReceiver::class.java)

        mediaSession.run {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(mediaSessionCallback)
            setMediaButtonReceiver(PendingIntent.getBroadcast(applicationContext, 0, mediaButtonIntent, 0))
        }

        val httpDataSourceFactory = OkHttpDataSourceFactory(OkHttpClient(), Util.getUserAgent(this, "vmplayer"), null)
        val cache = SimpleCache(File("${this.cacheDir.absolutePath}/exoplayer"), LeastRecentlyUsedCacheEvictor(1024 * 1024 * 30))
        dataSourceFactory = CacheDataSourceFactory(cache, httpDataSourceFactory, CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        extractorsFactory = DefaultExtractorsFactory()
        initializePlayer()
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaSessionCallback.onPlay()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                mediaSessionCallback.onPause()
                mHandler.postDelayed(delayedRunnable, TimeUnit.SECONDS.toMillis(30))
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mediaSessionCallback.onPause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
        }
    }

    private fun updateTrackUI(message: UpdateUIMessage) {
        EventBus.getDefault().post(message)
    }

    private val mediaSessionCallback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle) {
            super.onPrepareFromMediaId(mediaId, extras)

            if (mediaId == Constants.TRACK) {
                val position = extras.getInt(Constants.POSITION)

                val track = mediaRepository.getTrackByPosition(position)

                if (track.id != currentPlayingTrackId) {
                    mediaRepository.setCurrentPosition(position)
                    prepareTrack(track.getAudioUri())
                    mediaSession.setMetadata(setTrackMediaMetaData(track))
                    currentPlayingTrackId = track.id
                    source = Source.TRACK

                    with(track) {
                        updateTrackUI(UpdateUIMessage(title,
                            artist,
                            albumId,
                            null,
                            duration,
                            albumName,
                            Source.TRACK,
                            id,
                            track.inPlaylist))
                    }


                    onPlay()

                } else if (mExoPlayer.playWhenReady) {
                    onPause()
                } else if (!mExoPlayer.playWhenReady) {
                    onPlay()
                }
            } else if (mediaId == Constants.INIT) {
                val track = mediaRepository.getCurrentTrack()

                prepareTrack(track.getAudioUri())
                mediaSession.setMetadata(setTrackMediaMetaData(track))
                currentPlayingTrackId = track.id
                source = Source.TRACK

                with(track) {
                    updateTrackUI(UpdateUIMessage(title,
                        artist,
                        albumId,
                        null,
                        duration,
                        albumName,
                        Source.TRACK,
                        id,
                        track.inPlaylist))
                }
            } else {
                val uri = Uri.parse(extras.getString(Constants.RADIO_URL))
                val name = extras.getString(Constants.RADIO_TITLE)
                val imageUrl = extras.getString(Constants.RADIO_IMAGE)
                val radioId = extras.getString(Constants.RADIO_ID).toLong()
                val favicon = radioRepository.currentPlayingStation!!.favicon

                mediaSession.setMetadata(setRadioMediaMetaData(radioRepository.currentPlayingStation!!,
                    BitmapFactory.decodeResource(resources, R.drawable.album_art_default)))

                if (favicon!!.isNotEmpty()) {
                    Picasso.get()
                        .load(favicon)
                        .placeholder(R.drawable.album_art_default)
                        .error(R.drawable.album_art_default)
                        .into(target)
                }

                currentPlayingTrackId = -1
                source = Source.RADIO
                stopInterval()
                updateTrackUI(UpdateUIMessage("", name, 0, Uri.parse(imageUrl), 0, "", Source.RADIO, radioId, false))
                prepareRadiostation(uri)

                onPlay()

            }
        }

        override fun onPlay() {
            super.onPlay()

            if (!(application as App).isUpdated) {
                EventBus.getDefault().post(ShowPanelMessage(true))
                (application as App).isUpdated = true
            }

            if (!mExoPlayer.playWhenReady) {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                    startForegroundService(Intent(applicationContext, PlayerService::class.java))
                } else {
                    startService(Intent(applicationContext, PlayerService::class.java))
                }

                if (!isAudioFocusRequest) {


                    val audioFocusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.requestAudioFocus(audioFocusRequest)
                    } else {
                        audioManager.requestAudioFocus(
                            audioFocusChangeListener,
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN
                        )
                    }

                    if (audioFocusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        return
                    }
                }

                isAudioFocusRequest = true
                mediaSession.isActive = true
                mExoPlayer.playWhenReady = true

                mediaSession.setPlaybackState(
                    stateBuilder.setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F
                    ).build()
                )
            }
            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            currentState = PlaybackStateCompat.STATE_PLAYING
            mHandler.removeCallbacks(delayedRunnable)
            startInterval(source)
            refreshNotification(currentState)
        }

        override fun onPause() {
            super.onPause()

            if (mExoPlayer.playWhenReady) {
                mExoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
                stopInterval()

                if (isAudioFocusRequest) {
                    isAudioFocusRequest = false

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    } else {
                        audioManager.abandonAudioFocus(audioFocusChangeListener)
                    }
                }

                mediaSession.setPlaybackState(
                    stateBuilder.setState(
                        PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F
                    ).build()
                )
            }
            currentState = PlaybackStateCompat.STATE_PAUSED
            refreshNotification(currentState)
        }

        override fun onSeekTo(pos: Long) {
            super.onSeekTo(pos)
            mExoPlayer.seekTo(pos)
        }

        override fun onSkipToNext() {
            super.onSkipToNext()
            mExoPlayer.stop()
            val track = mediaRepository.getNextTrackByClick()
            mediaSession.setMetadata(setTrackMediaMetaData(track))
            currentPlayingTrackId = track.id
            prepareTrack(track.getAudioUri())
            source = Source.TRACK

            with(track) {
                updateTrackUI(UpdateUIMessage(title, artist, albumId, null, duration, albumName, Source.TRACK, id, track.inPlaylist))
            }

            onPlay()

        }

        override fun onSkipToPrevious() {
            super.onSkipToPrevious()

            if ((mExoPlayer.currentPosition / 1000) > 5) {
                mExoPlayer.seekTo(0)
            } else {

                mExoPlayer.stop()
                val track = mediaRepository.getPrevTrack()
                mediaSession.setMetadata(setTrackMediaMetaData(track))
                currentPlayingTrackId = track.id
                prepareTrack(track.getAudioUri())
                source = Source.TRACK

                with(track) {
                    updateTrackUI(
                        UpdateUIMessage(
                            title,
                            artist,
                            albumId,
                            null,
                            duration,
                            albumName,
                            Source.TRACK,
                            id,
                            track.inPlaylist
                        )
                    )
                }

                onPlay()
            }
        }

        override fun onStop() {
            super.onStop()

            if (mExoPlayer.playWhenReady) {
                unregisterReceiver(becomingNoisyReceiver)
            }

            if (isAudioFocusRequest) {
                isAudioFocusRequest = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest)
                } else {
                    audioManager.abandonAudioFocus(audioFocusChangeListener)
                }
            }

            mediaSession.isActive = false
            currentState = PlaybackStateCompat.STATE_STOPPED
            refreshNotification(currentState)
            stopSelf()
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            if (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE) {
                mediaRepository.setShuffleMode(Constants.SHUFFLE_MODE_OFF)
            } else {
                mediaRepository.setShuffleMode(Constants.SHUFFLE_MODE_ON)
            }
        }

        override fun onSetRepeatMode(repeatMode: Int) {
            when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ALL -> mediaRepository.setLoopMode(Constants.ALL_LOOP_MODE)
                PlaybackStateCompat.REPEAT_MODE_ONE -> mediaRepository.setLoopMode(Constants.ONE_LOOP_MODE)
                PlaybackStateCompat.REPEAT_MODE_NONE -> mediaRepository.setLoopMode(Constants.NO_LOOP_MODE)
                else -> mediaRepository.setLoopMode(Constants.NO_LOOP_MODE)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return PlayerBinder()
    }

    inner class PlayerBinder: Binder() {
        fun getMediaSessionToken(): MediaSessionCompat.Token = mediaSession.sessionToken
        fun getProgressData(): MutableLiveData<Int> {
            return progressData
        }
        fun getCurrentSource() = source
    }

    private fun initializePlayer() {
        mExoPlayer = ExoPlayerFactory.newSimpleInstance(
            DefaultRenderersFactory(this),
            DefaultTrackSelector(), DefaultLoadControl()
        )
        mExoPlayer.addListener(object : Player.EventListener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters?) {}

            override fun onSeekProcessed() {}

            override fun onTracksChanged(trackGroups: TrackGroupArray?, trackSelections: TrackSelectionArray?) {}

            override fun onPlayerError(error: ExoPlaybackException?) {}

            override fun onLoadingChanged(isLoading: Boolean) {}

            override fun onPositionDiscontinuity(reason: Int) {}

            override fun onRepeatModeChanged(repeatMode: Int) {}

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}

            override fun onTimelineChanged(timeline: Timeline?, manifest: Any?, reason: Int) {}

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    mExoPlayer.stop()
                    val track = mediaRepository.getNextTrack()
                    mediaSession.setMetadata(setTrackMediaMetaData(track))
                    currentPlayingTrackId = track.id
                    prepareTrack(track.getAudioUri())
                    source = Source.TRACK

                    with(track) {
                        updateTrackUI(UpdateUIMessage(title, artist, albumId, null, duration, albumName, Source.TRACK, id, track.inPlaylist))
                    }

                    mediaSessionCallback.onPlay()
                }
            }

        })
    }

    private fun prepareTrack(uri: Uri) {
        val mediaSource = ExtractorMediaSource.Factory(DefaultDataSourceFactory(this, "vmplayer"))
            .createMediaSource(uri)
        mExoPlayer.prepare(mediaSource)
    }

    private fun prepareRadiostation(uri: Uri) {
        val mediaSource = ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
        mExoPlayer.prepare(mediaSource)
    }

    private fun setTrackMediaMetaData(track: Track): MediaMetadataCompat {

        val bitmap: Bitmap = try {
            MediaStore.Images.Media.getBitmap(contentResolver, track.getImageUri())
        } catch (e: FileNotFoundException) {
            BitmapFactory.decodeResource(resources, R.drawable.album_art_default)
        }

        return metadataBuilder
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.albumName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.duration.toLong())
            .build()
    }

    private fun setRadioMediaMetaData(radioStation: RadioStation, bitmap: Bitmap?): MediaMetadataCompat {
        mediaSession.setMetadata(null)

        return metadataBuilder
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, radioStation.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, radioStation.country)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, radioStation.getTagsInfo())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
            .build()
    }

    private fun stopInterval() {
        try {
            disposable.dispose()
        } catch (e: UninitializedPropertyAccessException) {
            e.printStackTrace()
        }
    }

    private fun startInterval(source: Source) {
        if (source == Source.TRACK) {
            disposable = CompositeDisposable()

            subscription = Observable.interval(1, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    if (mExoPlayer.playWhenReady) {
                        progressData.value = (mExoPlayer.currentPosition / 1000).toInt()
                    } else {
                        disposable.dispose()
                    }
                },
                    {
                        Log.e("Error Log", "can't start interval", it)
                    })

            disposable.add(subscription)
        } else {
            progressData.value = 0
        }
    }

    private fun refreshNotification(playbackState: Int) {
        when (playbackState) {
            PlaybackStateCompat.STATE_PLAYING -> startForeground(Constants.NOTIFICATION_ID, getNotification(playbackState))
            PlaybackStateCompat.STATE_PAUSED -> {
                stopForeground(false)
                NotificationManagerCompat.from(this).notify(Constants.NOTIFICATION_ID, getNotification(playbackState))
            }
            else -> stopForeground(true)
        }
    }

    private fun getNotification(playbackState: Int): Notification {
        val builder: NotificationCompat.Builder = PlayerStyleHelper.from(applicationContext, mediaSession)

        builder.run {

            addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous_black_24dp, "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        applicationContext,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )

            if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
                addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_pause_black_24dp, "Pause",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            applicationContext,
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                        )
                    )
                )
            } else {
                addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_play_arrow_black_24dp, "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            applicationContext,
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                        )
                    )
                )
            }

            addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_next_black_24dp, "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        applicationContext,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
            )

            setStyle(
                android.support.v4.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            applicationContext,
                            PlaybackStateCompat.ACTION_STOP
                        )
                    )
            )

            setSmallIcon(R.mipmap.ic_launcher_ramp_round)
            color = ContextCompat.getColor(this@PlayerService, R.color.switch_thumb_normal_material_dark)
            setShowWhen(false)
            priority = NotificationCompat.PRIORITY_HIGH
            setOnlyAlertOnce(true)
            setChannelId(Constants.NOTIFICATION_DEFAULT_CHANNEL)
        }

        return builder.build()
    }

    override fun onDestroy() {
        try {
            disposable.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mExoPlayer.release()
        mediaSession.release()
        mHandler.removeCallbacks(delayedRunnable)

        super.onDestroy()
    }
}