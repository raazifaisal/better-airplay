package dev.better.airplay.data.engine

import android.view.Surface
import dev.better.airplay.domain.port.VideoSurface

/** Data-layer implementation of the domain's opaque [VideoSurface]: an Android [Surface]. */
class AndroidVideoSurface(val surface: Surface) : VideoSurface
