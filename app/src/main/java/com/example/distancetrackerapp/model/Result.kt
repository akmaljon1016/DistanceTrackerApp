package com.example.distancetrackerapp.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class Result(
    var distance:String,
    var time:String
):Parcelable