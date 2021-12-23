package com.mei.httpstest

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.mei.httpstest.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val mBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)
    }

    /**
     * Android 7.0 以上，测试环境 https 允许抓包方式1： 网络安全配置
     *
     * @param view
     */
    fun networkSecurityConfig(view: View) {
        val request = Request.Builder().url("https://juejin.cn/post/7017608469901475847").build()
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            mBinding.tvContent.text =
                "返回结果：\ncoroutineContext=${coroutineContext}\n\n${throwable.message}"
        }
        lifecycleScope.launch(exceptionHandler) {
            try {
                val response = async(Dispatchers.IO) {
                    OkHttpClient().newCall(request).execute()
                }

                mBinding.tvContent.text = "返回结果：${response.await().body()?.string()}"
            } catch (e: Exception) {
                mBinding.tvContent.text = "返回结果：${e.message}"
            }
        }
    }

    /**
     *  Android 7.0 以上，测试环境 https 允许抓包方式2：代码设置，信任所有的证书
     *
     * @param view
     */
    fun trustAllCertificate(view: View) {
        val request = Request.Builder().url("https://juejin.cn/post/7017608469901475847").build()
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            mBinding.tvContent.text =
                "返回结果：\ncoroutineContext=${coroutineContext}\n\n${throwable.message}"
        }
        lifecycleScope.launch(exceptionHandler) {
            try {
                val response = async(Dispatchers.IO) {
                    OkHttpClient.Builder().apply {
                        if (BuildConfig.DEBUG) {
                            // 测试环境，信任所有的证书，从而实现抓包
                            sslSocketFactory(createDebugSSLSocketFactory())
                        }
                    }.build().newCall(request).execute()
                }

                mBinding.tvContent.text = "返回结果：${response.await().body()?.string()}"
            } catch (e: Exception) {
                mBinding.tvContent.text = "返回结果：${e.message}"
            }
        }
    }

    private fun createDebugSSLSocketFactory(): SSLSocketFactory {
        val trustAllManager = object : X509TrustManager {

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {

            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return emptyArray() // 返回一个空数组， 信任所有证书，相当于return null
            }

        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }.socketFactory
    }
}