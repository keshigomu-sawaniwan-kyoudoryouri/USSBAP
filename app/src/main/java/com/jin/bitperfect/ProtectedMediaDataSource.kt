package com.jin.bitperfect

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@UnstableApi
class ProtectedMediaDataSource(private val context: Context) : BaseDataSource(false) {
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var key = ByteArray(0)
    private var logicalPosition = 0L
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: dataSpec.uri.lastPathSegment ?: throw IllegalArgumentException("Missing protected media id")
        val source = File(File(context.filesDir, "protected_media"), "$id.slm")
        val raf = RandomAccessFile(source, "r")
        if (raf.length() < 4) { raf.close(); throw EOFException("Invalid SLM container") }
        val magic = ByteArray(4); raf.readFully(magic)
        if (!magic.contentEquals(byteArrayOf(0x53, 0x4c, 0x4d, 0x31))) { raf.close(); throw IllegalStateException("Legacy SLM container is still migrating") }
        logicalPosition = dataSpec.position
        raf.seek(4L + logicalPosition)
        val available = (raf.length() - 4L - logicalPosition).coerceAtLeast(0L)
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        key = MessageDigest.getInstance("SHA-256").digest("SIGNAL-LAB-LIGHT:$id".toByteArray())
        file = raf; uri = dataSpec.uri; transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val count = file?.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (count < 0) return C.RESULT_END_OF_INPUT
        for (i in 0 until count) { val p=logicalPosition+i; buffer[offset+i]=(buffer[offset+i].toInt() xor key[(p%key.size).toInt()].toInt() xor (((p*31+17) and 0xff).toInt())).toByte() }
        logicalPosition += count; bytesRemaining -= count; bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() { try { file?.close() } finally { if (file != null) transferEnded(); file=null;uri=null } }
}

@UnstableApi
class RoutingDataSourceFactory(context: Context) : DataSource.Factory {
    private val appContext=context.applicationContext
    private val defaultFactory=DefaultDataSource.Factory(appContext)
    override fun createDataSource(): DataSource = object : DataSource {
        private var active:DataSource?=null
        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { }
        override fun open(dataSpec:DataSpec):Long { val chosen=if(dataSpec.uri.scheme=="slm")ProtectedMediaDataSource(appContext)else defaultFactory.createDataSource();active=chosen;return chosen.open(dataSpec) }
        override fun read(buffer:ByteArray,offset:Int,length:Int)=active?.read(buffer,offset,length)?:C.RESULT_END_OF_INPUT
        override fun getUri():Uri?=active?.uri
        override fun getResponseHeaders():Map<String,List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close(){active?.close();active=null}
    }
}
