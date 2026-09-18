package io.github.romanvht.byedpi.activities

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import io.github.romanvht.byedpi.R

/** Общие элементы тёмного интерфейса KKMProxy. */
object DashUi {

    /** Настраивает view_topbar: заголовок, кнопка «назад». */
    fun setupTopBar(activity: Activity, title: CharSequence): TopBar {
        val back = activity.findViewById<View>(R.id.topBack)
        back.setOnClickListener {
            (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
                ?: activity.finish()
        }
        activity.findViewById<TextView>(R.id.topTitle).text = title
        return TopBar(activity)
    }

    class TopBar(private val activity: Activity) {
        private val actions: LinearLayout = activity.findViewById(R.id.topActions)
        private val subtitle: TextView = activity.findViewById(R.id.topSubtitle)

        fun setSubtitle(text: CharSequence?) {
            subtitle.text = text
            subtitle.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        fun addAction(@DrawableRes icon: Int, description: String, onClick: (ImageView) -> Unit): ImageView {
            val view = activity.layoutInflater.inflate(R.layout.view_icon_button, actions, false) as ImageView
            view.setImageResource(icon)
            view.contentDescription = description
            view.setOnClickListener { onClick(view) }
            view.setOnLongClickListener {
                android.widget.Toast.makeText(activity, description, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            actions.addView(view)
            return view
        }
    }

    /** Уменьшает compound-иконки TextView (векторы по умолчанию 24dp). */
    fun shrinkDrawables(view: TextView, sizeDp: Int = 16) {
        val size = (sizeDp * view.resources.displayMetrics.density).toInt()
        val drawables = view.compoundDrawablesRelative.map { drawable ->
            drawable?.mutate()?.apply { setBounds(0, 0, size, size) }
        }
        view.setCompoundDrawablesRelative(drawables[0], drawables[1], drawables[2], drawables[3])
    }

    /** Диалог с одним или двумя полями ввода в стиле приложения. */
    fun inputDialog(
        activity: Activity,
        title: CharSequence,
        firstHint: CharSequence,
        firstValue: CharSequence? = null,
        secondHint: CharSequence? = null,
        secondValue: CharSequence? = null,
        hint: CharSequence? = null,
        onOk: (first: String, second: String) -> Unit,
    ): androidx.appcompat.app.AlertDialog {
        val view = activity.layoutInflater.inflate(R.layout.dialog_dash_inputs, null)
        val firstLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputFirstLayout)
        val secondLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputSecondLayout)
        val first = view.findViewById<android.widget.EditText>(R.id.inputFirst)
        val second = view.findViewById<android.widget.EditText>(R.id.inputSecond)
        firstLayout.hint = firstHint
        first.setText(firstValue)
        if (secondHint == null) {
            secondLayout.visibility = View.GONE
        } else {
            secondLayout.hint = secondHint
            second.setText(secondValue)
        }
        view.findViewById<TextView>(R.id.inputHint).apply {
            text = hint
            visibility = if (hint.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        return androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onOk(first.text?.toString().orEmpty(), second.text?.toString().orEmpty())
            }
            .show()
    }

    fun tint(view: ImageView, @ColorRes color: Int) {
        ImageViewCompat.setImageTintList(
            view,
            ColorStateList.valueOf(ContextCompat.getColor(view.context, color)),
        )
    }
}
