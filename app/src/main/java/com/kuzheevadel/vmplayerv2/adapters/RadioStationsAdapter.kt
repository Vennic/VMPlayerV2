package com.kuzheevadel.vmplayerv2.adapters

import android.databinding.DataBindingUtil
import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.kuzheevadel.vmplayerv2.BR
import com.kuzheevadel.vmplayerv2.helper.BindServiceHelper
import com.kuzheevadel.vmplayerv2.common.Constants
import com.kuzheevadel.vmplayerv2.databinding.RadioStationItemBinding
import com.kuzheevadel.vmplayerv2.model.RadioStation
import com.kuzheevadel.vmplayerv2.repository.RadioRepository

class RadioStationsAdapter(private val bindServiceHelper: BindServiceHelper,
                           private val radioRepository: RadioRepository): RecyclerView.Adapter<RadioStationsAdapter.RadioViewHolder>() {

    var radioStationsList = mutableListOf<RadioStation>()

    init {
        val callback = object : MediaControllerCompat.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                super.onPlaybackStateChanged(state)
            }
        }

        bindServiceHelper.bindPlayerService(callback)
    }

    override fun onCreateViewHolder(parent: ViewGroup, pos: Int): RadioViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RadioStationItemBinding.inflate(inflater, parent, false)
        return RadioViewHolder(binding.root)
    }

    override fun getItemCount(): Int {
        return radioStationsList.size
    }

    override fun onBindViewHolder(viewHolder: RadioViewHolder, pos: Int) {
        val radioStation = radioStationsList[pos]

        val bundle = Bundle()
        bundle.putString(Constants.RADIO_URL, radioStation.url)
        bundle.putString(Constants.RADIO_TITLE, radioStation.name)
        bundle.putString(Constants.RADIO_IMAGE, radioStation.favicon)
        bundle.putString(Constants.RADIO_ID, radioStation.id)


        viewHolder.binding?.setVariable(BR.radioStation, radioStation)

        viewHolder.view.setOnClickListener {
            radioRepository.currentPlayingStation = radioStation
            bindServiceHelper.mediaControllerCompat?.transportControls?.prepareFromMediaId(Constants.RADIO_STATION, bundle)
        }

        viewHolder.binding?.executePendingBindings()

    }

    inner class RadioViewHolder(val view: View): RecyclerView.ViewHolder(view) {
        val binding: RadioStationItemBinding? = DataBindingUtil.bind(view)
    }

    fun unbindService() {
        bindServiceHelper.unbindPlayerService()
    }
}