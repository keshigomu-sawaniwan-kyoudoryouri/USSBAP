package com.jin.bitperfect

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import kotlin.math.*

data class StreamingDspState(
    var enabled: Boolean = false,
    var preset: Int = 0,
    var bass: Float = .10f,
    var compressor: Float = .18f,
    val gains: FloatArray = FloatArray(5)
) {
    fun applyPreset(id: Int) {
        preset = id.coerceIn(0, 5)
        val values = when (preset) {
            1 -> floatArrayOf(5.5f, 3f, .5f, -.5f, 1f).also { bass=.62f; compressor=.24f }
            2 -> floatArrayOf(-1.5f, .5f, 3.5f, 4f, 1.5f).also { bass=.12f; compressor=.34f }
            3 -> floatArrayOf(4.5f, 2.5f, 2.5f, 3f, 1f).also { bass=.52f; compressor=.38f }
            4 -> floatArrayOf(4f, -1f, -1.5f, 2.5f, 4.5f).also { bass=.38f; compressor=.22f }
            5 -> floatArrayOf(-2f, -.5f, 1.5f, 3.5f, 5f).also { bass=.08f; compressor=.18f }
            else -> FloatArray(5).also { bass=.10f; compressor=.18f }
        }
        values.copyInto(gains)
    }
}

object StreamingDspRuntime {
    @Volatile var state = StreamingDspState()
    @Volatile var controller: StreamingDspController? = null
    @Volatile var streaming = false
    @Volatile var backend = "DSP STANDBY"

    fun load(context: Context) {
        val p=context.getSharedPreferences("stream_dsp",Context.MODE_PRIVATE)
        val s=StreamingDspState(p.getBoolean("enabled",false),p.getInt("preset",0),p.getFloat("bass",.1f),p.getFloat("compressor",.18f))
        for(i in 0..4)s.gains[i]=p.getFloat("g$i",0f)
        state=s
    }
    fun save(context: Context) {
        val s=state;val e=context.getSharedPreferences("stream_dsp",Context.MODE_PRIVATE).edit().putBoolean("enabled",s.enabled).putInt("preset",s.preset).putFloat("bass",s.bass).putFloat("compressor",s.compressor)
        for(i in 0..4)e.putFloat("g$i",s.gains[i]);e.apply();controller?.apply(s)
    }
}

class StreamingDspController {
    private var sessionId=0
    private var equalizer: Equalizer?=null
    private var bass: BassBoost?=null
    private var dynamics: DynamicsProcessing?=null
    private val frequencies=floatArrayOf(60f,250f,1000f,4000f,12000f)

    @Synchronized fun attach(id:Int) { if(id==sessionId)return;release();sessionId=id;if(id>0)apply(StreamingDspRuntime.state) }
    @Synchronized fun apply(s:StreamingDspState) {
        if(!s.enabled || !StreamingDspRuntime.streaming || sessionId<=0){releaseEffects();StreamingDspRuntime.backend="DSP STANDBY";return}
        val active=mutableListOf<String>()
        if(equalizer==null)try{equalizer=Equalizer(0,sessionId).apply{enabled=true};active.add("EQ")}catch(_:Throwable){}
        if(bass==null)try{bass=BassBoost(0,sessionId).apply{enabled=true};active.add("BASS")}catch(_:Throwable){}
        if(dynamics==null)try{
            val config=DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,2,true,5,true,5,false,0,true).setPreferredFrameDuration(10f).build()
            dynamics=DynamicsProcessing(0,sessionId,config).apply{enabled=true};active.add("DP")
        }catch(_:Throwable){}
        try{bass?.setStrength((s.bass.coerceIn(0f,1f)*1000).roundToInt().toShort())}catch(_:Throwable){}
        try{
            val eq=equalizer!!;val range=eq.bandLevelRange
            for(b in 0 until eq.numberOfBands){val center=eq.getCenterFreq(b.toShort())/1000.0;var gain=0.0;for(i in 0..4){val oct=ln(center/frequencies[i])/ln(2.0);gain+=s.gains[i]*exp(-(oct*oct)/(2*.72*.72))};eq.setBandLevel(b.toShort(),(gain*100).roundToInt().coerceIn(range[0].toInt(),range[1].toInt()).toShort())}
        }catch(_:Throwable){}
        try{
            val dp=dynamics!!;val ratio=1f+s.compressor.coerceIn(0f,1f)*7f
            for(i in 0..4){dp.setPreEqBandAllChannelsTo(i,DynamicsProcessing.EqBand(true,frequencies[i],s.gains[i]));dp.setMbcBandAllChannelsTo(i,DynamicsProcessing.MbcBand(s.compressor>.01f,frequencies[i],8f,100f,ratio,-10f-s.compressor*18f,4f,-80f,1f,0f,0f))}
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true,true,0,2f,60f,10f,-1f,0f))
        }catch(_:Throwable){}
        StreamingDspRuntime.backend=if(active.isEmpty())"DSP ACTIVE" else "SESSION $sessionId / ${active.joinToString("+")}" 
    }
    @Synchronized fun setStreaming(active:Boolean){StreamingDspRuntime.streaming=active;apply(StreamingDspRuntime.state)}
    @Synchronized fun release(){releaseEffects();sessionId=0}
    private fun releaseEffects(){release(equalizer);equalizer=null;release(bass);bass=null;release(dynamics);dynamics=null}
    private fun release(e:AudioEffect?){try{e?.enabled=false;e?.release()}catch(_:Throwable){}}
}
