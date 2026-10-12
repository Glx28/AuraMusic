/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.innertube

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Email and password sign-in, without a WebView.
 *
 * Google's sign-in form is a plain HTML form: fetch it, post the typed credentials
 * together with the hidden fields it handed back, and the responses carry the same
 * session cookies a browser would have stored. Those cookies are what InnerTube
 * authenticates with (`Authorization: SAPISIDHASH ...`), so once they are in place the
 * rest of the app talks to YouTube as a signed-in session.
 *
 * Redirects are followed by hand on purpose. Google's sign-in hops through several
 * hosts, each handing back cookies of its own, and a client that follows redirects
 * internally only exposes the final response - which would silently drop the SAPISID
 * this all depends on.
 *
 * Two-factor prompts, CAPTCHAs and suspicious-sign-in interstitials are reported as
 * failures rather than half-applied: a session that still needs a second factor is not
 * a session, and accepting it would leave the app signed in with every write failing.
 */
object YouTubeEmailLogin {

    private const val SERVICE_LOGIN = "https://accounts.google.com/ServiceLogin"
    private const val CONTINUE_URL = "https://www.youtube.com/"
    private const val MAX_REDIRECTS = 8

    /** Cookies InnerTube needs to talk as a signed-in session. */
    private val REQUIRED_COOKIES = listOf("SAPISID", "SID", "HSID", "SSID", "APISID")

    class LoginException(message: String) : Exception(message)

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            // Redirects are walked manually so every hop's Set-Cookie is captured.
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    /**
     * Signs in and, on success, installs the session on [YouTube] and verifies it
     * against the account endpoint before reporting success.
     */
    suspend fun signIn(email: String, password: String): Result<AccountSummary> {
        if (email.isBlank() || password.isBlank()) {
            return Result.failure(LoginException("Enter both an email address and a password"))
        }

        val jar = LinkedHashMap<String, String>()

        return try {
            val loginUrl = Url("$SERVICE_LOGIN?service=youtube&continue=${CONTINUE_URL.urlEncode()}")
            val loginPage = request(loginUrl, jar).bodyAsText()
            val hiddenFields = parseHiddenFields(loginPage)
            if (hiddenFields.isEmpty()) {
                return Result.failure(LoginException("Google's sign-in form was not recognised"))
            }

            val form = Parameters.build {
                hiddenFields.forEach { (key, value) -> append(key, value) }
                append("Email", email.trim())
                append("Passwd", password)
            }
            val formUrl = Url(formAction(loginPage) ?: loginUrl.toString())

            var response: HttpResponse = client.post(formUrl) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
            }
            collectCookies(response, jar)
            var hops = 0
            while (!response.status.isSuccess() && hops < MAX_REDIRECTS) {
                val next = response.headers[HttpHeaders.Location]?.let { Url(resolve(formUrl, it)) }
                    ?: break
                response = request(next, jar)
                hops++
            }

            val body = response.bodyAsText()
            when {
                needsSecondFactor(body) || body.contains("/signin/v2/challenge", ignoreCase = true) ->
                    return Result.failure(
                        LoginException("This account needs a verification step - finish it with browser sign-in"),
                    )
                body.containsWrongPassword() ->
                    return Result.failure(LoginException("That email and password was rejected"))
                response.status == HttpStatusCode.BadRequest ->
                    return Result.failure(LoginException("That email and password was rejected"))
            }

            val cookieString = REQUIRED_COOKIES
                .mapNotNull { name -> jar[name]?.let { "$name=$it" } }
                .joinToString("; ")
            val sapisid = jar["SAPISID"]
            if (sapisid.isNullOrBlank()) {
                return Result.failure(
                    LoginException("Google accepted the request but returned no session - try browser sign-in"),
                )
            }

            // Install and prove the session before claiming success: a cookie set the
            // account endpoint rejects is not a login.
            val previousCookie = YouTube.cookie
            YouTube.cookie = cookieString
            val info = YouTube.accountInfo().getOrElse {
                YouTube.cookie = previousCookie
                return Result.failure(LoginException("YouTube rejected the session: ${it.message}"))
            }
            Result.success(
                AccountSummary(
                    name = info.name,
                    email = info.email.orEmpty(),
                    channelHandle = info.channelHandle.orEmpty(),
                    cookie = cookieString,
                ),
            )
        } catch (e: LoginException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(LoginException(e.message ?: "Sign-in failed"))
        }
    }

    data class AccountSummary(
        val name: String,
        val email: String,
        val channelHandle: String,
        val cookie: String,
    )

    private suspend fun request(url: Url, jar: MutableMap<String, String>): HttpResponse {
        val response = client.get(url) {
            if (jar.isNotEmpty()) {
                header(
                    HttpHeaders.Cookie,
                    jar.entries.joinToString("; ") { "${it.key}=${it.value}" },
                )
            }
        }
        collectCookies(response, jar)
        return response
    }

    /** Collects the cookies of one response into the jar, honouring deletions. */
    private fun collectCookies(response: HttpResponse, jar: MutableMap<String, String>) {
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { raw ->
            val first = raw.substringBefore(';')
            val name = first.substringBefore('=').trim()
            val value = first.substringAfter('=', "").trim()
            if (name.isEmpty()) return@forEach
            val maxAge = raw.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("Max-Age=", ignoreCase = true) }
                ?.substringAfter('=')?.toIntOrNull()
            // A cookie with an expired Max-Age is Google clearing it.
            if (maxAge != null && maxAge <= 0) jar.remove(name) else jar[name] = value
        }
    }

    /** Hidden inputs of the sign-in form, which have to be echoed back untouched. */
    private fun parseHiddenFields(html: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        val inputRegex = Regex("""<input[^>]*>""", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("""value=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        inputRegex.findAll(html).forEach { input ->
            val name = nameRegex.find(input.value)?.groupValues?.get(1) ?: return@forEach
            val value = valueRegex.find(input.value)?.groupValues?.get(1).orEmpty()
            fields[name] = value
        }
        return fields
    }

    /** The form's own action when the page declares one. */
    private fun formAction(html: String): String? =
        Regex("""<form[^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun resolve(base: Url, location: String): String = when {
        location.startsWith("http") -> location
        location.startsWith("/") -> "${base.protocol.name.lowercase()}://${base.host}$location"
        else -> "${base.protocol.name.lowercase()}://${base.host}/${base.encodedPath.removePrefix("/")}$location"
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun String.containsWrongPassword(): Boolean =
        contains("id=\"loginform\"", ignoreCase = true) ||
            contains("Wrong password", ignoreCase = true) ||
            contains("id=\"captcha-form\"", ignoreCase = true)

    private fun needsSecondFactor(body: String): Boolean =
        body.contains("challenge", ignoreCase = true) &&
            (body.contains("FactorPin", ignoreCase = true) || body.contains("Challenge", ignoreCase = true))
}