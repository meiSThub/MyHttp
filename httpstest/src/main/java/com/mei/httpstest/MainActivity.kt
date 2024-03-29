package com.mei.httpstest

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.BuildConfig
import com.mei.httpstest.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"

        private const val TEST_URL = "https://juejin.cn/post/7017608469901475847"
        // private const val TEST_URL = "https://www.baidu.com/"

        private const val JUEJIN_PUBLIC_KEY =
            "30820122300D06092A864886F70D01010105000382010F003082010A0282010100BE9A3B2A23981043035B331584BE30D500C41AAC286A43DAB889763E7BB715B044F6DF2445FBA9762F288FC68B81A853F1A9490F6F9141761F59B42065F827AC58EBC006207A2A27A1682C845F1830E046E12D168988B02254BE36E026B117C49A9B9F31AF3F4CD389395A1FB2F0AD1F96C650D6A0D856DF299796C0630AE73E92D83D67B697596E715DDDA70037AF0E39E49B3227AD26B4BCEEF72896D30ACAA0CC77A87AED79F8B637808C411E195B7737ACF931C112A11221A21DBFAD050B5DE28734FC852CD5E973428BD2CED7897990FA45A03D67C0FC655A81B521DD1F211024D06F30DEE0B1910E6483E342308D69A269DE5415F286229EFBB1A3088D0203010001"
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
        val request = Request.Builder().url(TEST_URL).build()
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            throwable.printStackTrace()
            mBinding.tvContent.text =
                "返回结果：\ncoroutineContext=${coroutineContext}\n\n${throwable.message}"
        }
        lifecycleScope.launch(exceptionHandler) {
            try {
                val response = withContext(Dispatchers.IO) {
                    val response = OkHttpClient().newCall(request).execute()
                    response.body()?.string()
                }

                mBinding.tvContent.text = "返回结果：${response}"
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
        val request = Request.Builder().url(TEST_URL).build()
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            throwable.printStackTrace()
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

    /**
     * 证书文件锁定
     *
     * @param view
     */
    fun pinCertificate(view: View) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            Log.i(TAG, "pinCertificate exception: ${Thread.currentThread()}")
            // mBinding.tvContent.text =
            //     "返回结果：\ncoroutineContext=${coroutineContext}\n${throwable.message}"
        }
        lifecycleScope.launch(exceptionHandler + Dispatchers.IO) {
            val request = Request.Builder().url(TEST_URL).build()
            // 执行网络请求
            val response = OkHttpClient.Builder().apply {
                // 1.使用证书文件作为InputStream加载KeyStore并创建一个Keystore
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)

                val certificateInputStream = resources.openRawResource(R.raw.juejin)
                keyStore.setCertificateEntry(
                    "juejin",
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(certificateInputStream)
                )

                // val certificateFactory = CertificateFactory.getInstance("X.509")
                // val certificates = certificateFactory.generateCertificates(certificateInputStream)
                // certificates.forEachIndexed { index, certificate ->
                //     val certificateAlias = index.toString()
                //     keyStore.setCertificateEntry(certificateAlias, certificate)
                // }

                // 2.使用创建的KeyStore创建一个TrustManager
                val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                )
                trustManagerFactory.init(keyStore)
                // 使用创建的KeyStore创建一个KeyManagerFactory对象
                val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm()
                )
                keyManagerFactory.init(keyStore, null)

                val keyManagers = keyManagerFactory.keyManagers
                val trustManagers = trustManagerFactory.trustManagers

                // 3.创建一个SSLSocketFactory对象
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(keyManagers, trustManagers, SecureRandom())

                // 4.把创建的SSLSocketFactory对象，设置为OkHttp，进行证书文件锁定
                sslSocketFactory(sslContext.socketFactory, trustManagers[0] as X509TrustManager)

            }.build().newCall(request).execute() // 执行网络请求
            // 返回结果
            val reponseBody = response.body()?.string()
            // 切换线程
            withContext(Dispatchers.Main) {
                Log.i(TAG, "pinCertificate: 请求成功->\n${reponseBody}")
                mBinding.tvContent.text = "返回结果：${reponseBody}"
            }
        }
    }

    /**
     * 证书指纹锁定，即证书的信息摘要锁定，sha256
     *
     * @param view
     */
    fun pinCertificateSha256(view: View) {

        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            throwable.printStackTrace()
            mBinding.tvContent.text =
                "返回结果：\ncoroutineContext=${coroutineContext}\n\n${throwable.message}"
        }
        lifecycleScope.launch(exceptionHandler + Dispatchers.IO) {
            try {
                val request = Request.Builder().url(TEST_URL).build()
                Log.i(TAG, "pinnerCertificate:1 ${Thread.currentThread()}")
                // 执行网络请求
                val response = OkHttpClient.Builder().apply {
                    if (BuildConfig.DEBUG) {// 信任了所有证书，表示Android7.0以上，也是可以使用Charles抓包的
                        // 测试环境，信任所有的证书，从而实现抓包
                        sslSocketFactory(createDebugSSLSocketFactory())
                    }

                    // 域名：juejin.cn，对应的证书信息摘要为：bCyTfyF4MY0Vx6sa6j+AYVRdHbhZvC2w3XvCAo6sMCg=
                    // 这样配置之后，就达到证书锁定的目的了
                    // 当连接代理之后，返回的证书是Charles的，该证书的信息摘要是：cnHzh8vcjR1J7nxBDCXGZtS7tXK5ldgNZgcAskXY95c=
                    // 这样证书校验就不通过
                    val juejinPinner = CertificatePinner.Builder()
                        .add("juejin.cn", "sha256/bCyTfyF4MY0Vx6sa6j+AYVRdHbhZvC2w3XvCAo6sMCg=")
                        .build()
                    this.certificatePinner(juejinPinner)
                }.build().newCall(request).execute()
                Log.i(TAG, "pinnerCertificate:2 ${Thread.currentThread()}")
                val reponseBody = response.body()?.string()
                // val reponseBody = response.await().body()?.string()
                // 切换线程
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "pinnerCertificate:3 ${Thread.currentThread()}")
                    Log.i(TAG, "pinnerCertificate: 请求成功->\n${reponseBody}")
                    mBinding.tvContent.text = "返回结果：${reponseBody}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 证书公钥锁定
     *
     * @param view
     */
    fun pinPublicKey(view: View) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.i(TAG, "pinPublicKey exception: ${Thread.currentThread()}")
            throwable.printStackTrace()
            // mBinding.tvContent.text =
            //     "返回结果：\ncoroutineContext=${coroutineContext}\n${throwable.message}"
        }
        lifecycleScope.launch(exceptionHandler + Dispatchers.IO) {
            val request = Request.Builder().url(TEST_URL).build()
            // 执行网络请求
            val response = OkHttpClient.Builder().apply {
                // 1.使用证书文件作为InputStream加载KeyStore并创建一个Keystore
                val certificateInputStream = resources.openRawResource(R.raw.juejin)
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null)
                keyStore.setCertificateEntry(
                    "juejin",
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(certificateInputStream)
                )

                // 2.使用创建的KeyStore创建一个TrustManager
                val defaultAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
                val trustManagerFactory = TrustManagerFactory.getInstance(defaultAlgorithm)
                trustManagerFactory.init(keyStore)
                // 3.创建一个SSLSocketFactory对象
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustManagerFactory.trustManagers, SecureRandom())
                // 4.把创建的SSLSocketFactory对象，设置为OkHttp，进行证书文件锁定
                sslSocketFactory(sslContext.socketFactory)

            }.build().newCall(request).execute() // 执行网络请求
            // 返回结果
            val reponseBody = response.body()?.string()
            // 切换线程
            withContext(Dispatchers.Main) {
                Log.i(TAG, "pinCertificate: 请求成功->\n${reponseBody}")
                mBinding.tvContent.text = "返回结果：${reponseBody}"
            }
        }
    }
}