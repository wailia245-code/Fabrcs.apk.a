package com.fabrics.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

class PdfDocumentBridge(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val requests = HashMap<String, String>()
    private val lock = Any()

    private fun id() = UUID.randomUUID().toString()
    private fun result(req: String, status: String, message: String = "", path: String? = null, cacheKey: String? = null): String =
        JSONObject().apply {
            put("requestId", req); put("status", status); put("message", message); put("mime", "application/pdf")
            if (path != null) put("filePath", path)
            if (cacheKey != null) put("cacheKey", cacheKey)
        }.toString()

    @JavascriptInterface fun renderPdf(html: String, optionsJson: String): String {
        val req = id(); val options = JSONObject(optionsJson.ifBlank { "{}" }); val cacheKey = options.optString("cacheKey", "")
        val pending = result(req, "pending", "جاري إنشاء PDF")
        main.post { renderOnMain(req, html, cacheKey, options) }
        return pending
    }

    private fun renderOnMain(req: String, html: String, cacheKey: String, options: JSONObject) {
        if (html.length > 8_000_000) { complete(req, result(req, "error", "حجم المستند يتجاوز الحد المسموح")); return }
        val wv = WebView(activity)
        wv.setBackgroundColor(Color.WHITE)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                main.postDelayed({ writePdf(req, view, cacheKey, options) }, 120)
            }
        }
        val width = 794
        wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1_000_000, android.view.View.MeasureSpec.AT_MOST))
        wv.layout(0, 0, width, 1_000_000)
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
    }

    private fun writePdf(req: String, view: WebView, cacheKey: String, options: JSONObject) {
        try {
            val widthPx = view.width.coerceAtLeast(1)
            val contentHeight = view.contentHeight.coerceAtLeast(view.height)
            val pageWidth = 595
            val pageHeight = 842
            val scale = pageWidth.toFloat() / widthPx.toFloat()
            val sourcePageHeight = (pageHeight / scale).toInt().coerceAtLeast(1)
            val pages = ((contentHeight + sourcePageHeight - 1) / sourcePageHeight).coerceIn(1, 100)
            val dir = File(activity.cacheDir, "pdf_documents").apply { mkdirs() }
            val file = File(dir, "AW_${req}.pdf")
            val doc = PdfDocument()
            for (i in 0 until pages) {
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create()
                val page = doc.startPage(info)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                canvas.save(); canvas.scale(scale, scale); canvas.translate(0f, -(i * sourcePageHeight).toFloat())
                view.draw(canvas); canvas.restore()
                doc.finishPage(page)
            }
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            if (file.length() <= 0L || file.length() > 50L * 1024L * 1024L) throw IllegalStateException("حجم PDF غير صالح")
            complete(req, result(req, "success", "تم إنشاء PDF", file.absolutePath, cacheKey))
        } catch (e: Exception) {
            complete(req, result(req, "error", e.message ?: "تعذر إنشاء PDF"))
        } finally { view.destroy() }
    }

    @JavascriptInterface fun previewPdf(filePath: String, optionsJson: String): String = launchFile(reqMethod = "previewPdf", filePath = filePath)
    @JavascriptInterface fun printPdf(filePath: String, optionsJson: String): String = launchFile(reqMethod = "printPdf", filePath = filePath)
    @JavascriptInterface fun sharePdf(filePath: String, optionsJson: String): String = launchFile(reqMethod = "sharePdf", filePath = filePath)
    @JavascriptInterface fun savePdf(filePath: String, optionsJson: String): String = launchFile(reqMethod = "savePdf", filePath = filePath)

    private fun launchFile(reqMethod: String, filePath: String): String {
        val req = id(); val pending = result(req, "pending", "جاري التنفيذ")
        io.execute {
            try {
                val file = File(filePath); require(file.isFile && file.length() > 5) { "ملف PDF غير موجود" }
                val head = ByteArray(5); FileInputStream(file).use { it.read(head) }
                require(String(head, Charsets.US_ASCII) == "%PDF-") { "الملف ليس PDF" }
                main.post {
                    try {
                        when (reqMethod) {
                            "previewPdf" -> openPreview(req, file)
                            "printPdf" -> openPrint(req, file)
                            "sharePdf" -> openShare(req, file)
                            "savePdf" -> openSave(req, file)
                        }
                    } catch (e: Exception) { complete(req, result(req, "error", e.message ?: "تعذر تنفيذ العملية")) }
                }
            } catch (e: Exception) { complete(req, result(req, "error", e.message ?: "تعذر تنفيذ العملية")) }
        }
        return pending
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)

    private fun openPreview(req: String, file: File) {
        val i = Intent(activity, PdfPreviewActivity::class.java).apply { putExtra("filePath", file.absolutePath) }
        activity.startActivity(i); complete(req, result(req, "success", "تم فتح المعاينة", file.absolutePath))
    }

    private fun openShare(req: String, file: File) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri(file)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "مشاركة المستند")); complete(req, result(req, "success", "تم فتح المشاركة", file.absolutePath))
    }

    private fun openPrint(req: String, file: File) {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val job = "Abdelmajeed-Document"
        pm.print(job, PdfFilePrintAdapter(file, job), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        complete(req, result(req, "success", "تم فتح الطباعة", file.absolutePath))
    }

    private fun openSave(req: String, file: File) {
        val title = file.name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, title); addCategory(Intent.CATEGORY_OPENABLE) }
        activity.startActivityForResult(i, SaveRequest.next(req, file.absolutePath)); complete(req, result(req, "success", "تم فتح الحفظ", file.absolutePath))
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val req = SaveRequest.request(requestCode) ?: return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) { complete(req.req, result(req.req, "cancelled", "تم إلغاء الحفظ")); return true }
        io.execute {
            try {
                activity.contentResolver.openOutputStream(data.data!!).use { out -> requireNotNull(out); FileInputStream(File(req.path)).use { input -> input.copyTo(out) } }
                main.post { Toast.makeText(activity, "تم حفظ PDF بنجاح", Toast.LENGTH_SHORT).show(); complete(req.req, result(req.req, "success", "تم حفظ PDF", req.path)) }
            } catch (e: Exception) { complete(req.req, result(req.req, "error", e.message ?: "تعذر حفظ PDF")) }
        }
        return true
    }

    private fun complete(req: String, json: String) {
        main.post {
            val escaped = JSONObject.quote(json)
            activity.window.decorView.rootView.findViewById<WebView>(com.fabrics.app.R.id.webView)?.evaluateJavascript("window.AWNativeIO&&window.AWNativeIO._nativeResult($escaped);", null)
        }
    }

    private class PdfFilePrintAdapter(private val file: File, private val job: String) : PrintDocumentAdapter() {
        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: android.os.CancellationSignal?, callback: LayoutResultCallback?, extras: android.os.Bundle?) {
            if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
            callback?.onLayoutFinished(PrintDocumentInfo.Builder(job).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true)
        }
        override fun onWrite(pages: Array<android.print.PageRange>?, destination: android.os.ParcelFileDescriptor?, cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback?) {
            try {
                if (cancellationSignal?.isCanceled == true) { callback?.onWriteCancelled(); return }
                FileInputStream(file).use { input -> FileOutputStream(destination!!.fileDescriptor).use { out -> input.copyTo(out) } }
                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (e: Exception) { callback?.onWriteFailed(e.message) }
        }
    }

    object SaveRequest {
        private var seq = 40000
        private val map = HashMap<Int, Pair<String,String>>()
        @Synchronized fun next(req:String,path:String):Int { val c=++seq; map[c]=req to path; return c }
        @Synchronized fun request(c:Int):Req? { val p=map.remove(c) ?: return null; return Req(p.first,p.second) }
        data class Req(val req:String,val path:String)
    }
}
