package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.internal.SharedConstants
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.parser.Tag
import io.kapaseker.kharcho.select.Elements
import io.kapaseker.kharcho.select.Selector

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
class FormElement
/**
 * Create a new, standalone form element.
 *
 * @param tag        tag of this element
 * @param baseUri    the base URI
 * @param attributes initial attributes
 */
    (tag: Tag, baseUri: String?, attributes: Attributes?) :
    Element(tag, baseUri, attributes) {
    private val linkedEls = Elements()

    /**
     * Get the list of form control elements associated with this form.
     * @return form controls associated with this element.
     */
    fun elements(): Elements {
        // As elements may have been added or removed from the DOM after parse, prepare a new list that unions them:
        val els: Elements = select(submittable) // current form children
        for (linkedEl in linkedEls) {
            if (linkedEl!!.ownerDocument() != null && !els.contains(linkedEl)) {
                els.add(linkedEl) // adds previously linked elements, that weren't previously removed from the DOM
            }
        }

        return els
    }

    /**
     * Add a form control element to this form.
     * @param element form control to add
     * @return this form element, for chaining
     */
    fun addElement(element: Element?): FormElement {
        linkedEls.add(element)
        return this
    }

    override fun removeChild(out: Node) {
        super.removeChild(out)
        linkedEls.remove(out)
    }

    override fun clone(): FormElement {
        return super.clone() as FormElement
    }

    companion object {
        // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
        private val submittable =
            Selector.evaluatorOf(StringUtil.join(SharedConstants.FormSubmitTags, ", "))
    }
}
