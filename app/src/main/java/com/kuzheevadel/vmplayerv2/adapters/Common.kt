package com.kuzheevadel.vmplayerv2.adapters

import android.databinding.BindingAdapter
import android.net.Uri
import android.support.v7.widget.AppCompatImageView
import android.view.View
import com.kuzheevadel.vmplayerv2.R
import com.squareup.picasso.Picasso
import jp.wasabeef.picasso.transformations.RoundedCornersTransformation

interface ClickHandler {
    fun click(view: View)
}

@BindingAdapter(value = ["app:url"])
fun loadRoundedCornersImage(view: AppCompatImageView, uri: Uri?) {
    Picasso.get()
        .load(uri)
        .fit()
        .placeholder(R.drawable.album_art_default)
        .transform(RoundedCornersTransformation(20, 3))
        .into(view)
}

@BindingAdapter(value = ["app:album_url"])
fun loadImage(view: AppCompatImageView, uri: Uri?) {
    val stringUri = uri.toString()

    if (stringUri.startsWith("content", true)) {
        Picasso.get().load(uri)
            .fit()
            .placeholder(R.drawable.album_art_default)
            .into(view)
    } else {
        try {
            Picasso.get().load(uri.toString())
                .fit()
                .placeholder(R.drawable.album_art_default)
                .into(view)
        } catch (e: Exception) {
            view.setImageResource(R.drawable.album_art_default)
        }

    }
}

@BindingAdapter(value = ["app:radio_thumb"])
fun loadRadioImage(view: AppCompatImageView, url: String?) {
    try {
        Picasso.get().load(url)
            .fit()
            .error(R.drawable.album_art_default)
            .placeholder(R.drawable.album_art_default)
            .transform(RoundedCornersTransformation(20, 3))
            .into(view)
    } catch (e: Exception) {
        view.setImageResource(R.drawable.album_art_default)
    }

}