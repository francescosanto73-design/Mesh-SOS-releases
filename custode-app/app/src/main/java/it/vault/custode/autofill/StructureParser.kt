package it.vault.custode.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/** Adattatore tra la struttura di Android (AssistStructure) e [FieldInfo]. */
class ParsedStructure(
    val packageName: String,
    val webDomain: String?,
    val ids: List<AutofillId>,
    val fields: List<FieldInfo>,
)

object StructureParser {
    fun parse(s: AssistStructure): ParsedStructure? {
        val ids = ArrayList<AutofillId>(); val fields = ArrayList<FieldInfo>(); var web: String? = null

        fun visit(n: AssistStructure.ViewNode, depth: Int) {
            if (depth > 60) return
            n.webDomain?.takeIf { it.isNotBlank() }?.let { if (web == null) web = it }
            val id = n.autofillId
            if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val attrs = HashMap<String, String>()
                n.htmlInfo?.attributes?.forEach { attrs[it.first.lowercase()] = it.second }
                val hints = (n.autofillHints?.toList().orEmpty() + attrs["autocomplete"].orEmpty().split(' ')).filter { it.isNotBlank() }.map { it.lowercase() }
                ids += id
                fields += FieldInfo(
                    hints = hints,
                    inputType = n.inputType,
                    htmlType = attrs["type"]?.lowercase(),
                    name = listOfNotNull(attrs["name"], attrs["id"], attrs["placeholder"]).joinToString(" ").lowercase(),
                    label = listOfNotNull(n.idEntry, n.hint, attrs["aria-label"]).joinToString(" ").lowercase(),
                    visible = n.visibility == View.VISIBLE,
                    value = n.autofillValue?.takeIf { it.isText }?.textValue?.toString(),
                )
            }
            for (i in 0 until n.childCount) visit(n.getChildAt(i), depth + 1)
        }
        for (w in 0 until s.windowNodeCount) visit(s.getWindowNodeAt(w).rootViewNode, 0)
        val pkg = s.activityComponent?.packageName ?: return null
        return ParsedStructure(pkg, web, ids, fields)
    }
}
