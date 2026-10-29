package com.fartech.ftapp2.commonsKt

/** OkHttp may follow up 503/Retry-After:0 even with retryOnConnectionFailure(false).
 * Network interceptors run for each exchange of a Call, before request bytes are sent.
 * Requests/headers may change on follow-up: the authority is the Call identity, not their values.
 * Weak keys avoid retaining completed calls; an in-flight Call remains strongly referenced. */
internal object HttpSingleDispatch {
    fun install(builder: okhttp3.OkHttpClient.Builder) {
        if (builder.networkInterceptors().none { it is Guard }) builder.addNetworkInterceptor(Guard())
    }
    private class Guard : okhttp3.Interceptor {
        private val sent = java.util.WeakHashMap<okhttp3.Call, Boolean>()
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            synchronized(sent) {
                check(sent.put(chain.call(), true) == null) { "Single-dispatch HTTP follow-up/retry refused" }
            }
            return chain.proceed(chain.request())
        }
    }
}
