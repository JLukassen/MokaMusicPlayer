package com.mokamusic.player.audio.dsp

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class AutoEqMatch(val model: String, val source: String, val path: String)

/** User-triggered search of the same public AutoEq index used by DDCToolbox. */
object AutoEqCatalog {
    private const val INDEX = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md"
    private const val API = "https://api.github.com/repos/jaakkopasanen/AutoEq/contents/results/"
    private const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/"
    fun parseIndex(index: String, query: String): List<AutoEqMatch> {
        if (query.trim().length < 3) return emptyList()
        val regex = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?)(?: on .*)?$""")
        return index.lineSequence().mapNotNull {
            val m = regex.matchEntire(it.trim()) ?: return@mapNotNull null
            val path = m.groupValues[2]
            if (!m.groupValues[1].contains(query.trim(), true) || path.contains("..")) return@mapNotNull null
            AutoEqMatch(m.groupValues[1], m.groupValues[3], path)
        }.take(40).toList()
    }
    fun search(context: Context, query: String): List<AutoEqMatch> {
        if (query.trim().length < 3) return emptyList()
        val cache = File(context.cacheDir, "moka-autoeq-index.md")
        val content = if (cache.isFile && System.currentTimeMillis()-cache.lastModified()<86_400_000L)
            cache.readText() else download(INDEX, 2_000_000).also(cache::writeText)
        return parseIndex(content, query)
    }
    fun importVdc(context: Context, match: AutoEqMatch): Uri {
        require(match.path.isNotEmpty() && !match.path.contains(".."))
        val folderUrl = API + match.path.split('/').joinToString("/") {
            URLEncoder.encode(java.net.URLDecoder.decode(it, "UTF-8"), "UTF-8").replace("+", "%20")
        }
        val json = org.json.JSONArray(download(folderUrl, 180_000))
        val profile = (0 until json.length()).mapNotNull { json.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith("ParametricEQ.txt", true) }
            ?: error("No downloadable parametric profile for this model")
        val url = profile.getString("download_url")
        require(url.startsWith(RAW)) { "Unexpected profile URL" }
        val vdc = AutoEqVdc.convert(download(url, 64_000))
        VdcParser.parse(vdc)
        val file = File(context.filesDir, "autoeq/${match.path.hashCode().toUInt().toString(16)}.vdc")
        file.parentFile?.mkdirs()
        file.writeText(vdc)
        return Uri.fromFile(file)
    }
    private fun download(url: String, max: Int): String {
        val c=(URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=9000; readTimeout=17000
            setRequestProperty("User-Agent","MokaMusicPlayer/4.0")
        }
        try {
            check(c.responseCode in 200..299) { "AutoEq HTTP ${c.responseCode}" }
            return c.inputStream.use {
                val out=java.io.ByteArrayOutputStream()
                val block=ByteArray(8192)
                while(true) {
                    val n=it.read(block); if(n<0)break
                    check(out.size()+n<=max){"AutoEq response too large"}
                    out.write(block,0,n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}

object AutoEqVdc {
    data class Filter(val type:String,val hz:Double,val db:Double,val q:Double)
    fun convert(data: String): String {
        val preamp=Regex("""(?m)^Preamp:\s*([-\d.]+) dB""").find(data)?.groupValues?.get(1)?.toDoubleOrNull()?:0.0
        require(preamp in -40.0..10.0)
        val filterRegex=Regex("""(?m)^Filter \d+: ON (PK|LSC|HSC|LS|HS) Fc ([\d.]+) Hz Gain ([-\d.]+) dB Q ([\d.]+)""")
        val filters=filterRegex.findAll(data).map {
            Filter(it.groupValues[1],it.groupValues[2].toDouble(),it.groupValues[3].toDouble(),it.groupValues[4].toDouble())
        }.toList()
        require(filters.isNotEmpty() && filters.size<=32)
        filters.forEach{require(it.hz in 10.0..22_000.0 && it.db in -30.0..30.0 && it.q in 0.1..20.0)}
        return buildString {
            for(fs in listOf(44100,48000,88200,96000,176400,192000)){
                val co=mutableListOf(10.0.pow(preamp/20.0),0.0,0.0,0.0,0.0)
                filters.filter{it.hz<fs/2.0}.forEach{co.addAll(design(it,fs))}
                append("SR_$fs:")
                append(co.joinToString(","){java.lang.String.format(java.util.Locale.US,"%.12g",it)})
                appendLine()
            }
        }
    }
    private fun design(f:Filter,fs:Int):List<Double>{
        val w=2*PI*f.hz/fs;val cs=cos(w);val sn=sin(w)
        val a=10.0.pow(f.db/40.0);val alpha=sn/(2*f.q)
        val values=when(f.type){
            "PK"->listOf(1+alpha*a,-2*cs,1-alpha*a,1+alpha/a,-2*cs,1-alpha/a)
            else->{
                val term=2*sqrt(a)*alpha
                if(f.type.startsWith("LS"))
                    listOf(a*((a+1)-(a-1)*cs+term),2*a*((a-1)-(a+1)*cs),a*((a+1)-(a-1)*cs-term),(a+1)+(a-1)*cs+term,-2*((a-1)+(a+1)*cs),(a+1)+(a-1)*cs-term)
                else
                    listOf(a*((a+1)+(a-1)*cs+term),-2*a*((a-1)+(a+1)*cs),a*((a+1)+(a-1)*cs-term),(a+1)-(a-1)*cs+term,2*((a-1)-(a+1)*cs),(a+1)-(a-1)*cs-term)
            }
        }
        val a0=values[3]
        return listOf(values[0]/a0,values[1]/a0,values[2]/a0,-values[4]/a0,-values[5]/a0)
    }
}
