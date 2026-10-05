package com.fabrics.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var pdfBridge: PdfDocumentBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView=findViewById(R.id.webView)
        val s=webView.settings
        s.javaScriptEnabled=true; s.domStorageEnabled=true; s.databaseEnabled=true
        s.allowFileAccess=true; s.allowContentAccess=true
        s.allowFileAccessFromFileURLs=true; s.allowUniversalAccessFromFileURLs=true
        s.mediaPlaybackRequiresUserGesture=false; s.loadWithOverviewMode=true; s.useWideViewPort=true
        webView.webViewClient=WebViewClient(); webView.webChromeClient=WebChromeClient()
        pdfBridge=PdfDocumentBridge(this)
        webView.addJavascriptInterface(pdfBridge,"AWNativeIOBridge")
        webView.loadUrl("file:///android_asset/v17.7.7-PRODUCTION-FINAL.html")
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        if(pdfBridge.onActivityResult(requestCode,resultCode,data)) return
        super.onActivityResult(requestCode,resultCode,data)
    }

    override fun onBackPressed(){ if(webView.canGoBack()) webView.goBack() else super.onBackPressed() }
}
