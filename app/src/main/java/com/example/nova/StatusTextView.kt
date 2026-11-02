package com.example.nova

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Надпись статуса на главном экране.
 *
 * ## Что здесь было
 *
 * Раньше это был `StrokeTextView`: обводка букв, свечение под текстом и капсула
 * с ореолом из двенадцати размытых слоёв. Ореол и был тем «жирным градиентом»,
 * который владелец попросил убрать, — и по делу: двенадцать слоёв размытия
 * складывались в цветное пятно шире самой надписи, а вид при этом жил в
 * программном слое, то есть кадр стоил полного растра во весь экран.
 *
 * ## Что осталось
 *
 * Только роль: подпись состояния, которую переводит `NovaLanguage` в момент
 * показа. Ни одного `Paint`, ни одного слоя, ни одной перерисовки сверх той,
 * что заказывает сам `TextView`. Вес, размер и разрядку задаёт разметка, цвет —
 * `MainActivity`; всё это статично и стоит одно наложение текста.
 *
 * Имя класса сознательно перестало обещать обводку: класса с обводкой больше нет.
 */
class StatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr), NovaLanguage.LocalizedText {

    // Текст переводится в момент показа: код ставит русский исходник (см. NovaLanguage).
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)

    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}
