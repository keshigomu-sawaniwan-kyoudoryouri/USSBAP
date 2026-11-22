package com.jin.bitperfect

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())

        request.headers().forEach { (name, values) ->
            values.forEach { value ->
                requestBuilder.addHeader(name, value)
            }
        }

        val data = request.dataToSend()
        val body = data?.toRequestBody(null)
        val method = request.httpMethod()

        requestBuilder.method(method, body)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseHeaders = mutableMapOf<String, List<String>>()
            response.headers.forEach { pair ->
                responseHeaders[pair.first] = listOf(pair.second)
            }

            if (response.code == 429) {
                throw ReCaptchaException("rate limited", request.url())
            }

            return ExtractorResponse(
                response.code,
                response.message,
                responseHeaders,
                response.body?.string() ?: "",
                response.request.url.toString()
            )
        }
    }
}
