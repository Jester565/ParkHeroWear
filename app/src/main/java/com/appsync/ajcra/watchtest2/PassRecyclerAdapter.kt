package com.appsync.ajcra.watchtest2

import android.graphics.Bitmap
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.dis.ajcra.fastpass.fragment.DisPass
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import java.text.SimpleDateFormat
import java.util.*
import android.util.DisplayMetrics
import android.util.Log


class PassRecyclerAdapter(private var dataset: ArrayList<DisPass>, private var screenWidth: Int, private var screenHeight: Int): RecyclerView.Adapter<PassRecyclerAdapter.ViewHolder>() {
    private var dateParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    private var dateDispFormat = SimpleDateFormat("MM/dd/yyyy")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        var view = LayoutInflater.from(parent?.context).inflate(R.layout.row_pass, parent, false)
        var viewHolder = ViewHolder(view)
        return viewHolder
    }

    override fun getItemCount(): Int {
        return dataset.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        var pass = dataset.get(position)
        Log.d("STATE", "IMG_WIDTH: " + screenWidth + " IMG HEIGHT: " + screenHeight)
        var bitmap = encodeAsBitmap(pass.id(), BarcodeFormat.CODE_128, screenWidth * 2, (screenHeight.toFloat() * 0.875f).toInt())
        holder.barcodeImg.setImageBitmap(bitmap)
        holder.idText.text = pass.id()
        holder.nameText.text = pass.name()
        holder.typeText.text = pass.type()
        var expDTStr = pass.expirationDT()
        if (expDTStr != null) {
            var expDate = dateParser.parse(expDTStr)
            holder.expDateText.text = dateDispFormat.format(expDate)
        }
    }

    private val WHITE = -0x1
    private val BLACK = -0x1000000

    @Throws(WriterException::class)
    fun encodeAsBitmap(contents: String?, format: BarcodeFormat, img_width: Int, img_height: Int): Bitmap? {
        if (contents == null) {
            return null
        }
        var hints: MutableMap<EncodeHintType, Any>? = null
        val encoding = guessAppropriateEncoding(contents)
        if (encoding != null) {
            hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints!![EncodeHintType.CHARACTER_SET] = encoding
        }
        val writer = MultiFormatWriter()
        val result: BitMatrix
        try {
            result = writer.encode(contents, format, img_width, img_height, hints)
        } catch (iae: IllegalArgumentException) {
            return null
        }

        val width = result.getWidth()
        val height = result.getHeight()
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (result.get(x, y)) BLACK else WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun guessAppropriateEncoding(contents: CharSequence): String? {
        // Very crude at the moment
        for (i in 0 until contents.length) {
            if (contents[i].toInt() > 0xFF) {
                return "UTF-8"
            }
        }
        return null
    }

    class ViewHolder: RecyclerView.ViewHolder {
        var idText: TextView
        var nameText: TextView
        var expDateText: TextView
        var typeText: TextView
        var barcodeImg: ImageView

        constructor(itemView: View)
                :super(itemView) {
            idText = itemView.findViewById(R.id.passrow_id)
            nameText = itemView.findViewById(R.id.passrow_name)
            expDateText = itemView.findViewById(R.id.passrow_expDate)
            typeText = itemView.findViewById(R.id.passrow_type)
            barcodeImg = itemView.findViewById(R.id.passrow_barcode)
        }
    }
}