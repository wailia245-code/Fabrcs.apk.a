package com.fabrics.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

class PdfPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path=intent.getStringExtra("filePath") ?: run { finish(); return }
        val root=ScrollView(this)
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(8,8,8,8) }
        root.addView(list, ViewGroup.LayoutParams(-1,-2)); setContentView(root)
        val pfd=ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer=PdfRenderer(pfd)
        for(i in 0 until renderer.pageCount){
            val page=renderer.openPage(i)
            val scale=2f
            val bmp=Bitmap.createBitmap((page.width*scale).toInt(),(page.height*scale).toInt(),Bitmap.Config.ARGB_8888)
            page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close()
            list.addView(ZoomImageView(this).apply { setImageBitmap(bmp); adjustViewBounds=true; scaleType=ImageView.ScaleType.MATRIX; setPadding(0,0,0,12) },ViewGroup.LayoutParams(-1,-2))
        }
        renderer.close(); pfd.close()
    }

    private class ZoomImageView(context: android.content.Context): ImageView(context) {
        private val matrix=Matrix()
        private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(d:ScaleGestureDetector):Boolean {
                val f=d.scaleFactor.coerceIn(0.75f,1.25f); matrix.postScale(f,f,d.focusX,d.focusY); imageMatrix=matrix; return true
            }
        })
        override fun onTouchEvent(e:MotionEvent):Boolean { detector.onTouchEvent(e); return true }
    }
}
